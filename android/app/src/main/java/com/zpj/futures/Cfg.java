package com.zpj.futures;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App 配置：普通项明文，密钥类项用 AndroidKeyStore 派生的 AES-GCM 加密后再落盘。 */
public class Cfg {

    private static final String FILE = "ff_cfg";
    private static final String ALIAS = "ff_secret_v1";
    private static final String K_LLM_KEY = "llm_key";
    private static final String K_GITEE = "gitee_token";

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
        return v == null || v.trim().isEmpty() ? "https://api.deepseek.com/v1" : v.trim();
    }

    public void setLlmBase(String v) {
        sp.edit().putString("llm_base", safe(v)).apply();
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
        sp.edit().putString(K_LLM_KEY, seal(safe(v))).apply();
    }

    public String giteeToken() {
        return unseal(sp.getString(K_GITEE, ""));
    }

    public void setGiteeToken(String v) {
        sp.edit().putString(K_GITEE, seal(safe(v))).apply();
    }

    public int activeProduct() {
        return sp.getInt("active_product", 0);
    }

    public void setActiveProduct(int i) {
        sp.edit().putInt("active_product", i).apply();
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

    private static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            SecretKey k = key();
            if (k == null) return plain;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            return plain;
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
