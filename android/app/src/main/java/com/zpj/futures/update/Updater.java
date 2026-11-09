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
import java.nio.charset.StandardCharsets;

/**
 * 从 Gitee 私有仓库的 Release 自助更新。
 * 私有仓库读取 Release 与下载 APK 都需要个人令牌，令牌由用户在设置里填写。
 */
public class Updater {

    private static final String OWNER = "zpj100";
    private static final String REPO = "futures-forecast";
    private static final String API = "https://gitee.com/api/v5/repos/"
            + OWNER + "/" + REPO + "/releases/latest";

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
        public long size;
    }

    public Info check() {
        Info info = new Info();
        String token = cfg.giteeToken();
        if (token.isEmpty()) {
            info.message = "未填写 Gitee 令牌，无法读取私有仓库发行版";
            return info;
        }
        try {
            JSONObject o = new JSONObject(get(API + "?access_token=" + token, token));
            info.version = o.optString("tag_name", o.optString("name", ""));
            String body = o.optString("body", "");
            info.notes = body;
            // Release 正文里以 v2.0.0 形式写版本号，映射成 versionCode 便于比较
            info.versionCode = versionCode(info.version, body);
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.endsWith(".apk")) {
                        info.apkUrl = a.optString("browser_download_url", "");
                        info.size = a.optLong("size", 0);
                        break;
                    }
                }
            }
            info.hasUpdate = info.versionCode > BuildConfig.VERSION_CODE && !info.apkUrl.isEmpty();
            info.ok = true;
            if (!info.hasUpdate) {
                info.message = "已是最新版本 v" + BuildConfig.VERSION_NAME;
            }
        } catch (Exception e) {
            info.message = "检查失败：" + e.getMessage();
        }
        return info;
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

    /** 下载并触发安装。必须在后台线程调用。 */
    public String downloadAndInstall(Info info) {
        if (info.apkUrl.isEmpty()) return "没有可下载的安装包";
        try {
            String token = cfg.giteeToken();
            File dir = new File(ctx.getCacheDir(), "update");
            if (!dir.exists() && !dir.mkdirs()) return "无法创建缓存目录";
            File apk = new File(dir, "futures-" + info.version + ".apk");
            HttpURLConnection c = (HttpURLConnection) new URL(info.apkUrl).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setRequestProperty("User-Agent", SinaClient.UA);
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "token " + token);
            int code = c.getResponseCode();
            if (code != 200) return "下载失败：HTTP " + code;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            c.disconnect();
            install(apk);
            return "";
        } catch (Exception e) {
            return "下载失败：" + e.getMessage();
        }
    }

    private void install(File apk) {
        Uri uri = FileProvider.getUriForFile(ctx,
                ctx.getPackageName() + ".fileprovider", apk);
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                Intent set = new Intent(android.provider.Settings
                        .ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
                set.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(set);
                return;
            }
        }
        ctx.startActivity(it);
    }

    private static String get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", SinaClient.UA);
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", "token " + token);
        }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            String text = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + text);
            return text;
        } finally {
            c.disconnect();
        }
    }
}
