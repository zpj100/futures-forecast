package com.zpj.futures.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.zpj.futures.BuildConfig;
import com.zpj.futures.Cfg;
import com.zpj.futures.data.SinaClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 应用自助更新，**Gitee 为首要（也是唯一启用的）更新源**。
 *
 * <p>v2.1.1 修复：私有仓库的发行版直链
 * {@code https://gitee.com/{owner}/{repo}/releases/download/{tag}/{file}}
 * 无论带 {@code Authorization} 头还是 {@code ?access_token=} 都恒返回 403
 * （该地址要浏览器登录态 Cookie），这就是“下载失败 HTTP 403”的根因。
 * 唯一可用的取包路径是附件 API：
 * {@code GET /api/v5/repos/{owner}/{repo}/releases/{release_id}/attach_files/{attach_file_id}/download?access_token=xxx}
 * 它返回 303 跳到 foruda.gitee.com 的临时签名地址，跟随即可拿到 APK。
 * 附件 id / 体积只能从 {@code /releases/{id}/attach_files} 列表取——
 * {@code releases/latest} 的 assets 里既没有 id 也没有 size。
 *
 * <p>源优先级固定为 Gitee；若将来接入镜像站（如 GitHub），只能作为 Gitee 失败后的次要兜底，
 * 不得抢占 Gitee 首位（国内直连稳定性差异）。
 */
public class Updater {

    private static final String OWNER = "zpj100";
    private static final String REPO = "futures-forecast";
    private static final String API_BASE = "https://gitee.com/api/v5/repos/" + OWNER + "/" + REPO;

    /** 首要更新源标识，随检查结果回传给界面显示。 */
    public static final String SOURCE_GITEE = "gitee";

    private static final int MAX_HOPS = 6;

    private final Context ctx;
    private final Cfg cfg;

    public Updater(Context ctx, Cfg cfg) {
        this.ctx = ctx;
        this.cfg = cfg;
    }

    public static class Info {
        public boolean ok;
        public String message = "";
        public boolean hasUpdate;
        public String version = "";
        public int versionCode;
        public String notes = "";
        public String apkUrl = "";
        public String apkName = "";
        public long size;
        public String source = SOURCE_GITEE;
    }

    // ---------------- 检查更新 ----------------

    public Info check() {
        Info info = new Info();
        String token = cfg.giteeToken();
        if (token.isEmpty()) {
            info.message = "未填写 Gitee 令牌，无法读取私有仓库发行版";
            return info;
        }
        try {
            JSONObject rel = new JSONObject(apiGet("releases/latest", token));
            info.version = rel.optString("tag_name", rel.optString("name", ""));
            info.versionCode = versionCode(info.version, rel.optString("body", ""));
            info.notes = rel.optString("body", "");

            long releaseId = rel.optLong("id", 0);
            if (releaseId > 0) resolveAsset(info, releaseId, token);
            if (info.apkUrl.isEmpty()) resolveAssetFallback(info, rel);

            info.ok = true;
            info.hasUpdate = info.versionCode > BuildConfig.VERSION_CODE && !info.apkUrl.isEmpty();
            if (!info.hasUpdate) {
                info.message = info.apkUrl.isEmpty()
                        ? "最新发行版 " + info.version + " 未找到安装包附件"
                        : "已是最新版本 v" + BuildConfig.VERSION_NAME;
            }
        } catch (Exception e) {
            info.message = "检查失败：" + brief(e);
        }
        return info;
    }

    /** 走附件列表拿到 attach_file_id，构造可用的下载地址（私有仓库唯一可行路径）。 */
    private void resolveAsset(Info info, long releaseId, String token) {
        try {
            JSONArray arr = new JSONArray(apiGet("releases/" + releaseId + "/attach_files", token));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (!isApk(name)) continue;
                long id = a.optLong("id", 0);
                if (id <= 0) continue;
                info.apkName = name;
                info.size = a.optLong("size", 0);
                info.apkUrl = API_BASE + "/releases/" + releaseId
                        + "/attach_files/" + id + "/download";
                return;
            }
        } catch (Exception ignored) {
            // 老发行版可能没有附件列表接口，退回直链
        }
    }

    /** 兜底：公开仓库可直接用 assets 里的 browser_download_url。 */
    private void resolveAssetFallback(Info info, JSONObject rel) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (!isApk(name)) continue;
            info.apkName = name;
            info.apkUrl = a.optString("browser_download_url", "");
            if (info.size <= 0) info.size = a.optLong("size", 0);
            return;
        }
    }

    private static boolean isApk(String name) {
        return name != null && name.toLowerCase().endsWith(".apk");
    }

    /** 优先读取正文中的 `versionCode: 200`，退化为 tag 数字映射。 */
    static int versionCode(String tag, String body) {
        try {
            int i = body.indexOf("versionCode");
            if (i >= 0) {
                String tail = body.substring(i);
                StringBuilder num = new StringBuilder();
                boolean started = false;
                for (char ch : tail.toCharArray()) {
                    if (Character.isDigit(ch)) {
                        num.append(ch);
                        started = true;
                    } else if (started) {
                        break;
                    }
                }
                if (num.length() > 0) return Integer.parseInt(num.toString());
            }
        } catch (Exception ignored) {
        }
        try {
            String v = tag.replaceAll("[^0-9.]", "");
            String[] p = v.split("\\.");
            int major = p.length > 0 ? Integer.parseInt(p[0]) : 0;
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            int patch = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            return major * 10000 + minor * 100 + patch;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- 下载安装 ----------------

    /** 下载并触发安装。必须在后台线程调用；返回空串表示已发起安装。 */
    public String downloadAndInstall(Info info) {
        if (info.apkUrl == null || info.apkUrl.isEmpty()) return "没有可下载的安装包";
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) return "无法创建缓存目录";
        File part = new File(dir, "update.part");
        String tag = info.version == null || info.version.isEmpty() ? "latest" : info.version;
        File apk = new File(dir, "xueji-" + tag + ".apk");
        HttpURLConnection c = null;
        try {
            String url = withToken(info.apkUrl);
            c = openFollowing(url);
            int code = c.getResponseCode();
            if (code != 200) {
                String body = readError(c);
                c.disconnect();
                return "下载失败：HTTP " + code + hint(code) + (body.isEmpty() ? "" : "｜" + body);
            }
            long total = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.flush();
            }
            c.disconnect();
            c = null;

            if (total < 65536) return "下载失败：返回内容仅 " + total + " 字节，疑似不是安装包";
            if (info.size > 0 && total != info.size) {
                return "下载不完整：" + total + " / " + info.size + " 字节，请重试";
            }
            if (apk.exists() && !apk.delete()) return "无法覆盖旧安装包";
            if (!part.renameTo(apk)) return "无法写入安装包文件";
            install(apk);
            return "";
        } catch (Exception e) {
            return "下载失败：" + brief(e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 私有仓库下载走 API 附件接口，令牌只能用查询参数传递。 */
    private String withToken(String url) {
        String token = cfg.giteeToken();
        if (token.isEmpty() || url.contains("access_token=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "access_token=" + enc(token);
    }

    private static String hint(int code) {
        switch (code) {
            case 401:
                return "（令牌无效或已过期，请重新填写 Gitee 令牌）";
            case 403:
                return "（无下载权限：确认令牌具备 projects/articles 权限且未过期）";
            case 404:
                return "（发行版或附件已被删除）";
            default:
                return "";
        }
    }

    /**
     * 打开连接并手动跟随 301/302/303/307/308。
     * 附件接口会 303 跳到 foruda.gitee.com 的临时签名地址，Java 默认的自动跳转会丢掉查询参数，
     * 因此这里逐跳解析 Location。
     */
    private static HttpURLConnection openFollowing(String url) throws Exception {
        String cur = url;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", SinaClient.UA);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IllegalStateException("跳转地址为空");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            return c;
        }
        throw new IllegalStateException("跳转次数过多，下载地址异常");
    }

    private void install(File apk) {
        Uri uri = FileProvider.getUriForFile(ctx,
                ctx.getPackageName() + ".fileprovider", apk);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                Intent set = new Intent(android.provider.Settings
                        .ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
                set.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(set);
                return;
            }
        }
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(it);
    }

    // ---------------- HTTP ----------------

    private static String apiGet(String path, String token) throws Exception {
        String url = API_BASE + "/" + path
                + (path.contains("?") ? "&" : "?") + "access_token=" + enc(token);
        HttpURLConnection c = openFollowing(url);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = drain(in);
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code + hint(code)
                        + (text.isEmpty() ? "" : "｜" + trimMsg(text)));
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readError(HttpURLConnection c) {
        try {
            return trimMsg(drain(c.getErrorStream()));
        } catch (Exception e) {
            return "";
        }
    }

    private static String drain(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trimMsg(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return trimMsg(m);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
