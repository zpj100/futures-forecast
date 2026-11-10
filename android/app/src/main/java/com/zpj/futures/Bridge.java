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
import com.zpj.futures.quant.Forecaster;
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

    /**
     * 秒级曲线：bucketSec<=0 返回原始采集点，>0 按时间桶聚合成 OHLC。
     * 数据来自本机产生的 ticks，公开接口不提供秒级历史。
     */
    @JavascriptInterface
    public String tickSeries(String code, int bucketSec, int limit) {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            List<double[]> rows = store.tickSeries(code, bucketSec, limit);
            for (double[] r : rows) {
                JSONArray row = new JSONArray();
                row.put((long) r[0]);
                row.put(round(r[1]));
                row.put(round(r[2]));
                row.put(round(r[3]));
                row.put(round(r[4]));
                row.put((long) r[5]);
                arr.put(row);
            }
            o.put("code", code);
            o.put("bucket", bucketSec);
            o.put("points", arr);
            o.put("count", arr.length());
            o.put("last_tick", engine.lastTick());
        } catch (Exception ignored) {
        }
        return o.toString();
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
            JSONObject res = engine.syncHistory((msg, done, total) -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("msg", msg);
                    o.put("done", done);
                    o.put("total", total);
                    o.put("pct", total > 0 ? Math.round(done * 100f / total) : 0);
                } catch (Exception ignored) {
                }
                emit("onSyncStep", o.toString());
            });
            emit("onSync", res.toString());
        });
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 手动刷新预测：先增量补数据，再重算七档并回填到期记录。 */
    @JavascriptInterface
    public void predict() {
        if (engine.isForecastBusy()) {
            emit("onToast", q("预测正在刷新中，请稍候"));
            return;
        }
        pool.execute(() -> {
            JSONObject res = engine.forceForecast((msg, done, total) -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("msg", msg);
                    o.put("done", done);
                    o.put("total", total);
                    o.put("pct", total > 0 ? Math.round(done * 100f / total) : 0);
                } catch (Exception ignored) {
                }
                emit("onPredictStep", o.toString());
            });
            emit("onPredict", res.toString());
        });
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
                // 给模型喂多周期统计，而不是只喂 60 个采样点
                Map<String, List<Bar>> series = new java.util.HashMap<>();
                for (String p : new String[]{"1m", "5m", "15m", "30m", "60m", "1d"}) {
                    series.put(p, engine.bars(code, p));
                }
                List<double[]> recent = store.recentTicks(code, 40);
                LlmClient.Result r = llm.analyze(Products.nameOf(code), price, f,
                        series, recent, Products.unitOf(code));
                payload.put("ok", r.ok);
                payload.put("code", code);
                payload.put("text", r.text);
                payload.put("data", r.data == null ? JSONObject.NULL : r.data);
                // 逐周期落库：历史页可按周期看命中率
                if (r.ok && r.data != null) {
                    saveLlmForecasts(code, price, r.data);
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

    private void saveLlmForecasts(String code, double price, JSONObject d) {
        try {
            JSONArray hs = d.optJSONArray("horizons");
            if (hs == null) return;
            long now = System.currentTimeMillis();
            String overall = d.optString("action", joinReasons(d));
            for (int i = 0; i < hs.length(); i++) {
                JSONObject h = hs.optJSONObject(i);
                if (h == null) continue;
                String key = h.optString("key", "");
                String dir = h.optString("direction", "");
                if (key.isEmpty() || dir.isEmpty() || "—".equals(dir)) continue;
                double lo = h.optDouble("target_low", price);
                double hi = h.optDouble("target_high", price);
                String note = h.optString("note", "");
                String reason = note.isEmpty() ? overall
                        : note + (overall.isEmpty() ? "" : "｜" + overall);
                store.addForecast(code, key, dir, h.optDouble("confidence", 0.5),
                        (lo + hi) / 2, price, h.optDouble("confidence", 0.5),
                        "llm", reason, now, Forecaster.dueAt(key));
            }
        } catch (Exception ignored) {
        }
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
    public void installUpdate(String apkUrl, String version, long size) {
        pool.execute(() -> {
            Updater u = new Updater(ctx, cfg);
            Updater.Info info = new Updater.Info();
            info.apkUrl = apkUrl;
            info.version = version;
            info.size = size;
            emitUpdateProgress("start", 0, size);
            String err = u.downloadAndInstall(info,
                    (done, total) -> emitUpdateProgress("downloading", done, total));
            if (!err.isEmpty()) {
                emitUpdateProgress("error", 0, 0);
                emit("onToast", q(err));
            } else {
                emitUpdateProgress("done", size, size);
            }
        });
    }

    /** 下载进度：phase = start / downloading / done / error。 */
    private void emitUpdateProgress(String phase, long done, long total) {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("done", done);
            o.put("total", total);
            o.put("pct", total > 0 ? Math.min(100, Math.round(done * 100f / total)) : 0);
        } catch (Exception ignored) {
        }
        emit("onUpdateProgress", o.toString());
    }

    @JavascriptInterface
    public void toast(String msg) {
        main.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
}
