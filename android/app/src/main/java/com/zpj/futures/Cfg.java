package com.zpj.futures;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App 配置：普通项明文，密钥类项用 AndroidKeyStore 派生的 AES-GCM 加密后再落盘。 */
public class Cfg {

    private static final String TAG = "Cfg";
    private static final String FILE = "ff_cfg";
    private static final String ALIAS = "ff_secret_v1";
    private static final String K_LLM_KEY = "llm_key";
    private static final String K_GITEE = "gitee_token";
    private static final String DEFAULT_LLM_BASE = "https://api.deepseek.com/v1";
    private static final String DEEPSEEK_HOST = "api.deepseek.com";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private final SharedPreferences sp;

    public Cfg(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int refreshSec() {
        return clamp(sp.getInt("refresh_sec", 30));
    }

    public void setRefreshSec(int sec) {
        sp.edit().putInt("refresh_sec", clamp(sec)).apply();
    }

    private static int clamp(int s) {
        if (s < 5) return 5;
        if (s > 600) return 600;
        return s;
    }

    public String llmBase() {
        String v = sp.getString("llm_base", "");
        return v == null || v.trim().isEmpty() ? DEFAULT_LLM_BASE : v.trim();
    }

    /**
     * 保存 LLM 接口地址。只接受「公网 HTTPS」：
     * 明文 http 一律拒绝；host 为空、localhost / 127.0.0.1 / 内网私有地址一律拒绝；
     * 非 api.deepseek.com 的自定义 https 域名允许（中转），但记日志。
     * 校验不通过直接抛异常，调用方 saveSettings 会返回「保存失败」，
     * 从而保证 LlmClient 绝不会把 Bearer Key POST 到 http / 内网地址。
     */
    public void setLlmBase(String v) {
        String s = safe(v);
        if (s.isEmpty()) {
            sp.edit().putString("llm_base", "").apply();  // 清空即恢复默认
            return;
        }
        if (!isValidLlmBase(s)) {
            throw new IllegalArgumentException(
                    "llm_base 必须是公网 HTTPS 地址，禁止明文 HTTP 与本机/内网地址");
        }
        if (!DEEPSEEK_HOST.equalsIgnoreCase(hostOf(s))) {
            Log.i(TAG, "使用自定义 HTTPS LLM 中转域名：" + redact(hostOf(s))
                    + "（非 api.deepseek.com，已通过公网地址校验）");
        }
        sp.edit().putString("llm_base", s).apply();
    }

    /** 供 LlmClient 请求前二次校验：即使历史/被篡改的配置写成 http 或内网地址也拦住。 */
    public static boolean isValidLlmBase(String url) {
        try {
            URI u = new URI(url == null ? "" : url.trim());
            String scheme = u.getScheme();
            if (scheme == null || !"https".equalsIgnoreCase(scheme)) return false;
            if (u.getUserInfo() != null) return false;        // 禁止 userinfo 混淆
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;
            return !isBlockedHost(host);
        } catch (Exception e) {
            return false;
        }
    }

    private static String hostOf(String url) {
        try {
            String h = new URI(url.trim()).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isBlockedHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.US);
        if (h.isEmpty()) return true;
        if (h.equals("localhost") || h.endsWith(".localhost")) return true;
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        // IPv6 回环 / 链路本地 / 唯一本地
        if (h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) return true;
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")) return true;
        // 仅对数字 IPv4 做内网判定，避免一次配置校验触发 DNS 解析
        if (IPV4.matcher(h).matches()) {
            String[] p = h.split("\\.");
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            if (a == 127 || a == 0 || a == 10) return true;             // 回环 / 本机 / 私有 A
            if (a == 192 && b == 168) return true;                       // 私有 C
            if (a == 169 && b == 254) return true;                       // 链路本地
            if (a == 172 && b >= 16 && b <= 31) return true;             // 私有 B
            if (a == 100 && b >= 64 && b <= 127) return true;            // CGNAT
        }
        return false;
    }

    /**
     * 错误信息脱敏：移除 access_token=...、Bearer xxx、sk-xxx 等凭据，
     * 并折叠第三方响应体（成段 JSON）。调用方仍应只回传异常类型/状态码，不拼响应原文。
     */
    public static String redact(String s) {
        if (s == null) return "";
        String v = s;
        v = v.replaceAll("(?i)(access[_-]?token\\s*[=:]\\s*)[^&\\s\"']+", "$1<redacted>");
        v = v.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=\\-]+", "$1<redacted>");
        v = v.replaceAll("(?i)sk-[A-Za-z0-9._\\-]{4,}", "<redacted>");
        v = v.replaceAll("\\{[^{}]{60,}\\}", "<response-body>");
        v = v.replaceAll("\\[[^\\]]{60,}\\]", "<response-body>");
        return v;
    }

    public String llmModel() {
        String v = sp.getString("llm_model", "");
        return v == null || v.trim().isEmpty() ? "deepseek-chat" : v.trim();
    }

    public void setLlmModel(String v) {
        sp.edit().putString("llm_model", safe(v)).apply();
    }

    public String llmKey() {
        return unseal(sp.getString(K_LLM_KEY, ""));
    }

    public void setLlmKey(String v) {
        String sealed = seal(safe(v));
        if (sealed == null) {
            throw new IllegalStateException("密钥加密失败，已拒绝以明文保存（请检查设备安全设置）");
        }
        sp.edit().putString(K_LLM_KEY, sealed).apply();
    }

    public String giteeToken() {
        return unseal(sp.getString(K_GITEE, ""));
    }

    public void setGiteeToken(String v) {
        String sealed = seal(safe(v));
        if (sealed == null) {
            throw new IllegalStateException("密钥加密失败，已拒绝以明文保存（请检查设备安全设置）");
        }
        sp.edit().putString(K_GITEE, sealed).apply();
    }

    /** 界面风格：dark（默认黑夜） / light（白天）。 */
    public String theme() {
        String v = sp.getString("theme", "");
        return "light".equals(v) ? "light" : "dark";
    }

    public void setTheme(String v) {
        sp.edit().putString("theme", "light".equals(v) ? "light" : "dark").apply();
    }

    public int activeProduct() {
        return sp.getInt("active_product", 0);
    }

    public void setActiveProduct(int i) {
        sp.edit().putInt("active_product", i).apply();
    }

    /**
     * 已导入的预置行情库版本（assets/seed.db 的 seed_meta.version，形如 202611092054）。
     * 必须用 long：12 位时间戳版本号超出 int 范围，早期用 parseInt 会 NumberFormatException
     * 被吞成 0，导致预置库永远不导入（v2.1.2 修复）。现改为 long 解析。
     */
    public long seedVersion() {
        Object v = sp.getAll().get("seed_version");
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    public void setSeedVersion(long v) {
        sp.edit().putLong("seed_version", v).apply();
    }

    /** 预置库导入完成时间（0 表示从未导入）。 */
    public long seedImportedAt() {
        Object v = sp.getAll().get("seed_imported_at");
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    public void setSeedImportedAt(long ms) {
        sp.edit().putLong("seed_imported_at", ms).apply();
    }

    /** 最近一次历史同步的结果摘要，供设置页回显（排查“同步没反应”）。 */
    public String lastSyncMsg() {
        String v = sp.getString("last_sync_msg", "");
        return v == null ? "" : v;
    }

    public void setLastSyncMsg(String v) {
        sp.edit().putString("last_sync_msg", safe(v)).apply();
    }

    /** 最近一次历史同步的时间戳。 */
    public long lastSync() {
        return sp.getLong("last_sync", 0L);
    }

    public void setLastSync(long ms) {
        sp.edit().putLong("last_sync", ms).apply();
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }

    // ---------- crypto ----------

    private static SecretKey key() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) {
                return (SecretKey) ks.getKey(ALIAS, null);
            }
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        } catch (Exception e) {
            return null;
        }
    }

    /** 加密失败一律返回 null（绝不把令牌/Key 明文落盘）；空串表示清空，正常返回。 */
    private static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            SecretKey k = key();
            if (k == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String unseal(String blob) {
        if (blob == null || blob.isEmpty()) return "";
        int i = blob.indexOf(':');
        if (i <= 0) return blob;
        try {
            SecretKey k = key();
            if (k == null) return "";
            byte[] iv = Base64.decode(blob.substring(0, i), Base64.NO_WRAP);
            byte[] ct = Base64.decode(blob.substring(i + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
