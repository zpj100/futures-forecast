package com.zpj.futures;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.zpj.futures.ai.LlmClient;
import com.zpj.futures.data.Engine;
import com.zpj.futures.data.Products;
import com.zpj.futures.data.SinaClient;
import com.zpj.futures.db.Bar;
import com.zpj.futures.db.Store;
import com.zpj.futures.update.Updater;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView 与原生之间的桥。所有可能耗时的操作都丢到后台线程，
 * 完成后通过 window.FF.* 回调前端。
 */
public class Bridge {

    private final Context ctx;
    private final Cfg cfg;
    private final Engine engine;
    private final Store store;
    private final LlmClient llm;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile android.webkit.WebView web;

    public Bridge(Context ctx, Cfg cfg, Engine engine) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.engine = engine;
        this.store = Store.get(ctx);
        this.llm = new LlmClient(cfg);
    }

    public void attach(android.webkit.WebView w) {
        this.web = w;
    }

    public void detach() {
        this.web = null;
    }

    private void emit(String fn, String json) {
        android.webkit.WebView w = web;
        if (w == null) return;
        String js = "window.FF && window.FF." + fn + " && window.FF." + fn + "(" + json + ");";
        main.post(() -> {
            try {
                w.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ---------------- 数据 ----------------

    @JavascriptInterface
    public String state() {
        return engine.state().toString();
    }

    @JavascriptInterface
    public void refresh() {
        engine.refresh(true);
    }

    @JavascriptInterface
    public String kline(String code, String period, int limit) {
        JSONObject o = new JSONObject();
        try {
            String p = period == null || period.isEmpty() ? "1m" : period;
            JSONArray arr = new JSONArray();
            List<Bar> bars = engine.bars(code, p);
            if (bars.isEmpty()) bars = store.klines(code, p, limit);
            int from = Math.max(0, bars.size() - limit);
            for (int i = from; i < bars.size(); i++) {
                Bar b = bars.get(i);
                JSONArray row = new JSONArray();
                row.put(b.ts);
                row.put(round(b.o));
                row.put(round(b.h));
                row.put(round(b.l));
                row.put(round(b.c));
                row.put(round(b.v));
                arr.put(row);
            }
            o.put("code", code);
            o.put("period", p);
            o.put("bars", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public String sparkline(String code, int points) {
        return engine.sparkline(code, points).toString();
    }

    @JavascriptInterface
    public String listForecasts(String code, int limit) {
        return store.listForecasts(limit, code).toString();
    }

    @JavascriptInterface
    public String hitStats() {
        return store.hitStats().toString();
    }

    @JavascriptInterface
    public String dataStats() {
        return store.stats().toString();
    }

    @JavascriptInterface
    public void clearForecasts() {
        store.clearForecasts();
        emit("onToast", q("预测记录已清空"));
    }

    /** 同步最近 180 天历史（日线 + 各周期分钟线）。 */
    @JavascriptInterface
    public void syncHistory() {
        if (engine.isSyncing()) {
            emit("onToast", q("同步正在进行中，请稍候"));
            return;
        }
        emit("onToast", q("开始同步最近 180 天数据…"));
        pool.execute(() -> {
            JSONObject res = engine.syncHistory(msg -> emit("onSyncStep", q(msg)));
            emit("onSync", res.toString());
        });
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ---------------- 设置 ----------------

    @JavascriptInterface
    public String settings() {
        JSONObject o = new JSONObject();
        try {
            o.put("refresh_sec", cfg.refreshSec());
            o.put("llm_base", cfg.llmBase());
            o.put("llm_model", cfg.llmModel());
            o.put("has_llm_key", !cfg.llmKey().isEmpty());
            o.put("llm_key_mask", mask(cfg.llmKey()));
            o.put("has_gitee_token", !cfg.giteeToken().isEmpty());
            o.put("gitee_token_mask", mask(cfg.giteeToken()));
            o.put("theme", cfg.theme());
            o.put("version", BuildConfig.VERSION_NAME);
            o.put("version_code", BuildConfig.VERSION_CODE);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    /** payload: {refresh_sec, llm_base, llm_model, llm_key, gitee_token}；clear 表示清空。 */
    @JavascriptInterface
    public String saveSettings(String payload) {
        JSONObject res = new JSONObject();
        try {
            JSONObject o = new JSONObject(payload == null ? "{}" : payload);
            if (o.has("refresh_sec")) {
                int sec = o.optInt("refresh_sec", 30);
                cfg.setRefreshSec(sec);
                engine.reschedule();
            }
            if (o.has("llm_base")) cfg.setLlmBase(o.optString("llm_base"));
            if (o.has("llm_model")) cfg.setLlmModel(o.optString("llm_model"));
            String lk = o.optString("llm_key", "");
            if ("clear".equals(lk)) cfg.setLlmKey("");
            else if (!lk.isEmpty()) cfg.setLlmKey(lk);
            String gt = o.optString("gitee_token", "");
            if ("clear".equals(gt)) cfg.setGiteeToken("");
            else if (!gt.isEmpty()) cfg.setGiteeToken(gt);
            if (o.has("theme")) {
                cfg.setTheme(o.optString("theme"));
                applyNativeTheme();
            }
            res.put("ok", true);
            res.put("message", "已保存");
        } catch (Exception e) {
            try {
                res.put("ok", false);
                res.put("message", "保存失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return res.toString();
    }

    // ---------------- 主题 ----------------

    /** 前端切换主题时调用：写盘 + 同步原生底色与状态栏图标。 */
    @JavascriptInterface
    public void setTheme(String t) {
        cfg.setTheme(t);
        applyNativeTheme();
    }

    private void applyNativeTheme() {
        if (ctx instanceof MainActivity) {
            main.post(() -> ((MainActivity) ctx).applyThemeUi());
        }
    }

    // ---------------- 大模型 ----------------

    @JavascriptInterface
    public void llmAnalyze(final String code) {
        if (!llm.configured()) {
            emit("onLlm", "{\"ok\":false,\"code\":" + q(code)
                    + ",\"text\":\"未配置大模型 API Key，请先到「设置」填写\"}");
            return;
        }
        pool.execute(() -> {
            JSONObject payload = new JSONObject();
            try {
                JSONObject f = engine.forecasts().get(code);
                if (f == null) f = new JSONObject();
                Map<String, SinaClient.Quote> qs = engine.quotes();
                SinaClient.Quote quote = qs.get(code);
                double price = quote != null ? quote.last : f.optDouble("price", 0);
                List<double[]> recent = store.recentTicks(code, 60);
                LlmClient.Result r = llm.analyze(Products.nameOf(code), price, f,
                        recent, Products.unitOf(code));
                payload.put("ok", r.ok);
                payload.put("code", code);
                payload.put("text", r.text);
                payload.put("data", r.data == null ? JSONObject.NULL : r.data);
                // 落库留档，便于回看
                if (r.ok) {
                    JSONObject d = r.data;
                    String dir = d != null ? d.optString("direction", "震荡") : "震荡";
                    double conf = d != null ? d.optDouble("confidence", 0.5) : 0.5;
                    double lo = d != null ? d.optDouble("target_low", price) : price;
                    double hi = d != null ? d.optDouble("target_high", price) : price;
                    String reason = d != null ? joinReasons(d) : r.text;
                    store.addForecast(code, "1h", dir, conf, (lo + hi) / 2, price,
                            conf, "llm", reason, System.currentTimeMillis(),
                            System.currentTimeMillis() + 60 * 60_000L);
                }
            } catch (Exception e) {
                try {
                    payload.put("ok", false);
                    payload.put("code", code);
                    payload.put("text", "分析失败：" + e.getMessage());
                } catch (Exception ignored) {
                }
            }
            emit("onLlm", payload.toString());
        });
    }

    private static String joinReasons(JSONObject d) {
        JSONArray rs = d.optJSONArray("reasons");
        StringBuilder sb = new StringBuilder();
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) {
                if (i > 0) sb.append("；");
                sb.append(rs.optString(i));
            }
        }
        String action = d.optString("action", "");
        if (!action.isEmpty()) sb.append(sb.length() > 0 ? "；" : "").append(action);
        return sb.toString();
    }

    // ---------------- 更新 ----------------

    @JavascriptInterface
    public void checkUpdate() {
        pool.execute(() -> {
            Updater.Info info = new Updater(ctx, cfg).check();
            JSONObject o = new JSONObject();
            try {
                o.put("ok", info.ok);
                o.put("has_update", info.hasUpdate);
                o.put("version", info.version);
                o.put("version_code", info.versionCode);
                o.put("notes", info.notes == null ? "" : stripNotes(info.notes));
                o.put("message", info.message);
                o.put("apk_url", info.apkUrl);
                o.put("apk_name", info.apkName);
                o.put("size", info.size);
                o.put("source", info.source);
            } catch (Exception ignored) {
            }
            emit("onUpdate", o.toString());
        });
    }

    private static String stripNotes(String s) {
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    @JavascriptInterface
    public void installUpdate(String apkUrl, String version) {
        pool.execute(() -> {
            Updater u = new Updater(ctx, cfg);
            Updater.Info info = new Updater.Info();
            info.apkUrl = apkUrl;
            info.version = version;
            String err = u.downloadAndInstall(info);
            if (!err.isEmpty()) emit("onToast", q(err));
        });
    }

    @JavascriptInterface
    public void toast(String msg) {
        main.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
}
