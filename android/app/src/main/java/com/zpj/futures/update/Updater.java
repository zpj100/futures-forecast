package com.zpj.futures.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
 * <p>安全：下载/跳转 URL 只允许 https + gitee.com 域（含 foruda.gitee.com 子域），
 * 每一跳都校验、最多 6 跳；令牌只附加给白名单 URL；安装前校验包名/版本/签名。
 */
public class Updater {

    private static final String OWNER = "zpj100";
    private static final String REPO = "futures-forecast";
    private static final String API_BASE = "https://gitee.com/api/v5/repos/" + OWNER + "/" + REPO;

    /** 首要更新源标识，随检查结果回传给界面显示。 */
    public static final String SOURCE_GITEE = "gitee";

    private static final int MAX_HOPS = 6;
    /** 固定文件名，不再用 tag 拼文件名，防路径穿越。 */
    private static final String APK_FILE = "update.apk";

    /** 安装状态：真正调起安装器才算 done；未获「安装未知应用」权限返回 need_permission。 */
    public static final String ST_DONE = "done";
    public static final String ST_NEED_PERMISSION = "need_permission";

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

    /** 下载/安装结果。message 已经 {@link Cfg#redact(String)} 脱敏，可直接回前端。 */
    public static class Result {
        public String status;          // ST_DONE / ST_NEED_PERMISSION / "error"
        public String message = "";

        private Result(String status, String message) {
            this.status = status;
            this.message = message;
        }

        public static Result done() {
            return new Result(ST_DONE, "");
        }

        public static Result needPermission() {
            return new Result(ST_NEED_PERMISSION, "");
        }

        public static Result error(String message) {
            return new Result("error", Cfg.redact(message));
        }

        public boolean isDone() {
            return ST_DONE.equals(status);
        }
    }

    /**
     * 仅允许 https 且 host ∈ {gitee.com, *.gitee.com, foruda.gitee.com}；
     * 拒绝明文、拒绝 userinfo 混淆。跳转目标与令牌附加都复用这一判定。
     */
    private static boolean allowedHost(String url) {
        try {
            URI u = new URI(url == null ? "" : url.trim());
            if (!"https".equalsIgnoreCase(u.getScheme())) return false;
            if (u.getUserInfo() != null) return false;
            String h = u.getHost();
            if (h == null || h.isEmpty()) return false;
            h = h.toLowerCase(Locale.US);
            // *.gitee.com 已覆盖 foruda.gitee.com
            return h.equals("gitee.com") || h.endsWith(".gitee.com");
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 检查更新 ----------------

    /** 检查阶段回调：让界面的进度条从「不确定」平滑过渡到「确定」。 */
    public interface Stage {
        void onStage(String msg, int pct);
    }

    public Info check() {
        return check(null);
    }

    public Info check(Stage st) {
        Info info = new Info();
        String token = cfg.giteeToken();
        if (token.isEmpty()) {
            info.message = "未填写 Gitee 令牌，无法读取私有仓库发行版";
            return info;
        }
        try {
            if (st != null) st.onStage("正在读取 Gitee 发行版信息…", 0);
            JSONObject rel = new JSONObject(apiGet("releases/latest", token));
            info.version = rel.optString("tag_name", rel.optString("name", ""));
            info.versionCode = versionCode(info.version, rel.optString("body", ""));
            info.notes = rel.optString("body", "");
            if (st != null) st.onStage("已找到发行版 " + trimMsg(info.version) + "，正在解析安装包…", 55);

            long releaseId = rel.optLong("id", 0);
            if (releaseId > 0) resolveAsset(info, releaseId, token);
            if (info.apkUrl.isEmpty()) resolveAssetFallback(info, rel);
            if (st != null) {
                st.onStage(info.apkUrl.isEmpty() ? "未找到安装包附件" : "安装包信息已就绪，正在比对版本…", 85);
            }

            info.ok = true;
            info.hasUpdate = info.versionCode > BuildConfig.VERSION_CODE && !info.apkUrl.isEmpty();
            if (st != null) st.onStage(info.hasUpdate ? "发现新版本 " + info.version : "已是最新版本", 100);
            if (!info.hasUpdate) {
                info.message = info.apkUrl.isEmpty()
                        ? "最新发行版 " + info.version + " 未找到安装包附件"
                        : "已是最新版本 v" + BuildConfig.VERSION_NAME;
            }
        } catch (Exception e) {
            info.message = "检查失败：" + brief(e);
        }
        info.message = Cfg.redact(info.message);   // 统一脱敏（响应体/令牌/异常）
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

    /** 兜底：assets 里的 browser_download_url 仅在 Gitee 白名单域才采用，否则忽略。 */
    private void resolveAssetFallback(Info info, JSONObject rel) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (!isApk(name)) continue;
            String url = a.optString("browser_download_url", "");
            if (!allowedHost(url)) continue;   // 忽略非白名单直链，防投毒/凭据外泄
            info.apkName = name;
            info.apkUrl = url;
            if (info.size <= 0) info.size = a.optLong("size", 0);
            return;
        }
    }

    private static boolean isApk(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".apk");
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
            // 与 BuildConfig.VERSION_CODE（如 240）同量纲：v2.4.0 → 240。
            // 旧算法 major*10000+minor*100+patch 会得 20400，永远 > 240 造成「永远有更新」。
            return major * 100 + minor * 10 + patch;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- 下载安装 ----------------

    /** 下载进度回调；total<=0 表示服务端没给长度。 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 下载 + 校验 + 安装；必须在后台线程调用。返回状态（done / need_permission / error）。 */
    public Result downloadAndInstall(Info info) {
        return downloadAndInstall(info, null);
    }

    public Result downloadAndInstall(Info info, Progress cb) {
        if (info.apkUrl == null || info.apkUrl.isEmpty()) return Result.error("没有可下载的安装包");
        if (!allowedHost(info.apkUrl)) return Result.error("下载地址不在 Gitee 白名单内，已拒绝");
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) return Result.error("无法创建缓存目录");
        File part = new File(dir, "update.part");
        File apk = new File(dir, APK_FILE);
        HttpURLConnection c = null;
        try {
            String url = withToken(info.apkUrl);
            c = openFollowing(url);
            int code = c.getResponseCode();
            if (code != 200) {
                String body = readError(c);
                c.disconnect();
                c = null;
                return Result.error("下载失败：HTTP " + code + hint(code)
                        + (body.isEmpty() ? "" : "｜" + body));
            }
            long total = 0;
            long declared = c.getContentLengthLong();
            if (declared <= 0) declared = info.size;
            if (cb != null) cb.onProgress(0, declared);
            long lastAt = 0, lastBytes = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    long nowMs = System.currentTimeMillis();
                    if (cb != null && (nowMs - lastAt >= 200 || total - lastBytes >= 512 * 1024)) {
                        lastAt = nowMs;
                        lastBytes = total;
                        cb.onProgress(total, declared);
                    }
                }
                out.flush();
            }
            if (cb != null) cb.onProgress(total, declared > 0 ? declared : total);
            c.disconnect();
            c = null;

            if (total < 65536) return Result.error("下载失败：返回内容仅 " + total + " 字节，疑似不是安装包");
            if (info.size > 0 && total != info.size) {
                return Result.error("下载不完整：" + total + " / " + info.size + " 字节，请重试");
            }
            if (apk.exists() && !apk.delete()) return Result.error("无法覆盖旧安装包");
            if (!part.renameTo(apk)) return Result.error("无法写入安装包文件");
            if (!verifyApk(apk)) {
                apk.delete();
                return Result.error("安装包校验失败（包名/版本/签名不匹配），已删除");
            }
            return installResult(install(apk));
        } catch (Exception e) {
            return Result.error("下载失败：" + brief(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 直接安装已下载且已校验的 update.apk，不重新下载（供 need_permission 后续「继续安装」）。 */
    public Result installCached() {
        File apk = new File(new File(ctx.getCacheDir(), "update"), APK_FILE);
        if (!apk.isFile() || apk.length() < 65536) {
            return Result.error("没有已下载的安装包，请重新下载");
        }
        if (!verifyApk(apk)) {
            apk.delete();
            return Result.error("安装包校验失败，已删除，请重新下载");
        }
        return installResult(install(apk));
    }

    private static Result installResult(String st) {
        if (ST_DONE.equals(st)) return Result.done();
        if (ST_NEED_PERMISSION.equals(st)) return Result.needPermission();
        String msg = st != null && st.startsWith("error:")
                ? st.substring(6) : "安装器未能调起";
        return Result.error("安装失败：" + msg);
    }

    /** 私有仓库下载走 API 附件接口，令牌只能附加给白名单 URL（绝不带到外部主机）。 */
    private String withToken(String url) {
        String token = cfg.giteeToken();
        if (token.isEmpty() || url.contains("access_token=")) return url;
        if (!allowedHost(url)) return url;
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
     * 打开连接并手动跟随 301/302/303/307/308，最多 MAX_HOPS 跳。
     * 每一跳开头都校验 scheme/host 白名单；附件接口会 303 跳到 foruda.gitee.com 的临时签名地址。
     */
    private static HttpURLConnection openFollowing(String url) throws Exception {
        String cur = url;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (!allowedHost(cur)) {
                throw new IllegalStateException("下载地址不在 Gitee 白名单内，已拒绝");
            }
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
                continue;   // 下一跳开头再校验 scheme/host
            }
            return c;
        }
        throw new IllegalStateException("跳转次数过多，下载地址异常");
    }

    /**
     * 安装前校验：包名 == 当前包名、versionCode >= BuildConfig.VERSION_CODE、
     * 签名证书与自身应用签名一致（GET_SIGNATURES 兼容 minSdk 24）。
     */
    @SuppressWarnings("deprecation")
    private boolean verifyApk(File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo arc = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            if (arc == null) return false;
            if (!ctx.getPackageName().equals(arc.packageName)) return false;
            if (arc.versionCode < BuildConfig.VERSION_CODE) return false;
            Signature[] arcSig = arc.signatures;
            PackageInfo self = pm.getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            Signature[] selfSig = self.signatures;
            if (arcSig == null || arcSig.length == 0 || selfSig == null || selfSig.length == 0) {
                return false;
            }
            Set<String> mine = new HashSet<>();
            for (Signature s : selfSig) mine.add(s.toCharsString());
            for (Signature s : arcSig) if (mine.contains(s.toCharsString())) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回 ST_DONE / ST_NEED_PERMISSION / "error:原因"。 */
    private String install(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", apk);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                    Intent set = new Intent(android.provider.Settings
                            .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                    set.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(set);
                    } catch (Exception ignored) {
                    }
                    return ST_NEED_PERMISSION;   // 跳设置页，尚未真正安装
                }
            }
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(it);
            return ST_DONE;                       // 真正调起安装器
        } catch (Exception e) {
            return "error:" + brief(e);
        }
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
        return Cfg.redact(trimMsg(m));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
