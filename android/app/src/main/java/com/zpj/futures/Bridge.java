package com.zpj.futures;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.zpj.futures.ai.LlmClient;
import com.zpj.futures.data.Engine;
import com.zpj.futures.data.Products;
import com.zpj.futures.data.SinaClient;
import com.zpj.futures.db.Bar;
import com.zpj.futures.db.SeedImporter;
import com.zpj.futures.db.Store;
import com.zpj.futures.quant.Forecaster;
import com.zpj.futures.update.Updater;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

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
    /** close() 后置位：所有 emit / 主线程回调都不再打到已 destroy 的 WebView。 */
    private volatile boolean closed = false;
    /** checkUpdate() 缓存下来的更新信息，installUpdate / installCachedUpdate 复用。 */
    private volatile Updater.Info pendingUpdate;

    /** 服务端对照开关：serverGet 只允许 /api/ 开头的受控路径。 */
    private static final Pattern SERVER_PATH = Pattern.compile("^/api/[A-Za-z0-9/_.?=&%\\-]*$");

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

    /**
     * MainActivity.onDestroy 调用：关闭桥线程池、置空 web 引用，
     * 并阻止任何排队中的回调打到已 destroy 的 WebView。
     */
    public void close() {
        closed = true;
        web = null;
        pool.shutdownNow();
    }

    private void emit(String fn, String json) {
        if (closed) return;
        android.webkit.WebView w = web;
        if (w == null) return;
        String js = "window.FF && window.FF." + fn + " && window.FF." + fn + "(" + json + ");";
        main.post(() -> {
            if (closed || web == null) return;   // close() 可能在本 Runnable 排队后才执行
            try {
                w.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private SharedPreferences prefs() {
        // 与 Cfg 共用 ff_cfg 这个 SharedPreferences 文件，不新建存储
        return ctx.getSharedPreferences("ff_cfg", Context.MODE_PRIVATE);
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
            if (!periodAllowed(p)) p = "1m";                 // period 白名单
            int lim = Math.max(1, Math.min(5000, limit));     // limit 1..5000
            JSONArray arr = new JSONArray();
            List<Bar> bars = engine.bars(code, p);
            if (bars.isEmpty()) bars = store.klines(code, p, lim);
            int from = Math.max(0, bars.size() - lim);
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

    private static boolean periodAllowed(String p) {
        for (String x : Engine.PERIODS) if (x.equals(p)) return true;
        return false;
    }

    @JavascriptInterface
    public String sparkline(String code, int points) {
        return engine.sparkline(code, Math.max(1, Math.min(600, points))).toString();
    }

    /**
     * 秒级曲线：bucketSec<=0 返回原始采集点，>0 按时间桶聚合成 OHLC。
     * 数据来自本机产生的 ticks，公开接口不提供秒级历史。
     */
    @JavascriptInterface
    public String tickSeries(String code, int bucketSec, int limit) {
        JSONObject o = new JSONObject();
        try {
            int bsec = Math.max(0, Math.min(3600, bucketSec));   // bucketSec 0..3600
            int lim = Math.max(1, Math.min(5000, limit));          // limit 1..5000
            JSONArray arr = new JSONArray();
            List<double[]> rows = store.tickSeries(code, bsec, lim);
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
            o.put("bucket", bsec);
            o.put("points", arr);
            o.put("count", arr.length());
            o.put("last_tick", engine.lastTick());
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public String listForecasts(String code, int limit) {
        return store.listForecasts(Math.max(1, Math.min(5000, limit)), code).toString();
    }

    @JavascriptInterface
    public String hitStats() {
        return store.hitStats().toString();
    }

    @JavascriptInterface
    public String dataStats() {
        return store.stats().toString();
    }

    // ---------------- 本地库诊断 / 双端导出导入 / 重建（v2.6.0） ----------------

    /** 品种白名单（与 Products.CODES 一致）。 */
    private static boolean codeAllowed(String c) {
        if (c == null) return false;
        for (String x : Products.CODES) if (x.equals(c)) return true;
        return false;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** JSON 里不能有 NaN/Inf；导出时兜底成 0。 */
    private static double fin(double v) {
        return isFinite(v) ? v : 0.0;
    }

    /**
     * 本地库诊断快照。periods 覆盖三品种 × 六周期，legacy 为 5m/15m/30m/60m
     * 旧口径残留行数（1m/1d 恒为 0）；ticks 为秒级总体 n/min/max。
     */
    @JavascriptInterface
    public String localDbStatus() {
        JSONObject o = new JSONObject();
        try {
            JSONObject stats = store.klineStats();
            JSONObject legacy = store.countLegacyBars();
            int legacyTotal = 0;
            Iterator<String> it = legacy.keys();
            while (it.hasNext()) legacyTotal += legacy.optInt(it.next(), 0);

            JSONObject periods = new JSONObject();
            for (String code : Products.CODES) {
                for (String period : Engine.PERIODS) {
                    String key = code + "|" + period;
                    JSONObject s = stats.optJSONObject(key);
                    JSONObject one = new JSONObject();
                    one.put("n", s != null ? s.optInt("n", 0) : 0);
                    one.put("min", s != null ? s.optLong("min", 0) : 0);
                    one.put("max", s != null ? s.optLong("max", 0) : 0);
                    one.put("legacy", legacy.optInt(key, 0));
                    periods.put(key, one);
                }
            }
            o.put("periods", periods);
            o.put("ticks", store.tickStats());
            o.put("last_sync", cfg.lastSync());
            o.put("pending", pendingBarCount());
            o.put("seed_version", cfg.seedVersion());
            boolean migrated = prefs().getBoolean("db_migrated_260", false);
            o.put("migrated", migrated);
            o.put("note", buildDbNote(legacyTotal, migrated,
                    prefs().getInt("db_migrated_rows", 0)));
        } catch (Exception e) {
            try {
                o.put("error", Cfg.redact(brief(e)));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /** 从 ff_cfg/sync_pending 统计待续跑的 bar 总数（失败批 JSON 里的 bars 行数之和）。 */
    private int pendingBarCount() {
        try {
            String raw = prefs().getString("sync_pending", "");
            if (raw == null || raw.isEmpty()) return 0;
            JSONObject root = new JSONObject(raw);
            JSONArray batches = root.optJSONArray("batches");
            if (batches == null) return 0;
            int n = 0;
            for (int i = 0; i < batches.length(); i++) {
                JSONObject b = batches.optJSONObject(i);
                if (b == null) continue;
                JSONArray bars = b.optJSONArray("bars");
                if (bars != null) n += bars.length();
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private String buildDbNote(int legacyTotal, boolean migrated, long migratedRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("periods 覆盖三品种×六周期；legacy 为 5m/15m/30m/60m 旧口径残留行数"
                + "（1m/1d 不参与迁移）；ts 统一 UTC 毫秒整数");
        if (migrated) {
            if (migratedRows >= 0) {
                sb.append("；旧口径已迁移（删除 ").append(migratedRows).append(" 行）");
            } else {
                sb.append("；旧口径迁移未完成（可重建本地库重试）");
            }
        } else {
            sb.append("；旧口径迁移尚未执行");
        }
        sb.append("；当前旧口径残留 ").append(legacyTotal).append(" 行");
        return sb.toString();
    }

    /**
     * 导出本地 K 线供服务端同步。since 毫秒（严格大于，0=全部），ts 升序，
     * limit clamp 1..300；period 白名单六周期；非法返回 {"error":"..."}。
     */
    @JavascriptInterface
    public String exportBars(String code, String period, long since, int limit) {
        if (closed) return errJson("桥已关闭");
        if (!codeAllowed(code)) return errJson("非法的品种代码");
        if (!periodAllowed(period)) return errJson("非法的周期");
        int lim = Math.max(1, Math.min(300, limit));
        long s = Math.max(0L, since);
        try {
            List<Bar> rows = store.klinesSince(code, period, s, lim + 1);
            boolean hasMore = rows.size() > lim;
            if (hasMore) rows = rows.subList(0, lim);
            JSONArray arr = new JSONArray();
            long last = s;
            for (Bar b : rows) {
                JSONObject ob = new JSONObject();
                ob.put("ts", b.ts);
                ob.put("o", fin(b.o));
                ob.put("h", fin(b.h));
                ob.put("l", fin(b.l));
                ob.put("c", fin(b.c));
                ob.put("v", fin(b.v));
                arr.put(ob);
                last = b.ts;
            }
            JSONObject res = new JSONObject();
            res.put("code", code);
            res.put("period", period);
            res.put("bars", arr);
            res.put("next_since", last);
            res.put("has_more", hasMore);
            return res.toString();
        } catch (Exception e) {
            return errJson(Cfg.redact(brief(e)));
        }
    }

    /**
     * 导入 bars 数组 JSON 到本地库，返回净新增根数；失败返回 -1。
     * 校验 code/period、bars≤300、ts>0 且 < now+1 天、OHLC 有限；走 Store.upsertKlines。
     */
    @JavascriptInterface
    public int importBars(String code, String period, String barsJson) {
        if (closed || !codeAllowed(code) || !periodAllowed(period)) return -1;
        try {
            JSONArray arr = new JSONArray(barsJson == null ? "[]" : barsJson);
            if (arr.length() > 300) return -1;
            long upper = System.currentTimeMillis() + 86400_000L;
            List<Bar> bars = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) return -1;
                long ts = b.optLong("ts", 0);
                if (ts <= 0 || ts >= upper) return -1;
                double c = b.optDouble("c", Double.NaN);
                if (!isFinite(c)) return -1;
                double o = b.optDouble("o", c);
                double h = b.optDouble("h", c);
                double l = b.optDouble("l", c);
                double v = b.optDouble("v", 0.0);
                if (!isFinite(o) || !isFinite(h) || !isFinite(l) || !isFinite(v)) return -1;
                bars.add(new Bar(ts, o, h, l, c, v));
            }
            if (bars.isEmpty()) return 0;
            int before = store.countKline(code, period);
            store.upsertKlines(code, period, bars);
            return store.countKline(code, period) - before;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 重建本地库：后台线程清空 kline（保留 ticks/forecasts）→ 强制重新导入 seed.db
     * → engine.reloadCaches() → 触发一次前端刷新。逐阶段 emit onDbRebuild。
     * 与 syncHistory 共用 Engine 的 dbBusy 互斥，不阻塞 UI。
     */
    @JavascriptInterface
    public void rebuildLocalDb() {
        pool.execute(() -> {
            if (!engine.tryAcquireDb()) {
                emitRebuild("error", "数据库忙：同步或重建进行中", 0, 0);
                return;
            }
            try {
                emitRebuild("start", "清空本地 K 线（保留秒级与预测）…", 0, 0);
                store.clearKline();
                emitRebuild("step", "重置预置库版本并重新导入 seed.db…", 35, 0);
                cfg.setSeedVersion(0L);   // 绕过 importIfNeeded 的 seedVersion 跳过
                int rows = SeedImporter.importIfNeeded(ctx, cfg, store);
                if (rows < 0) {
                    emitRebuild("error", "预置库重新导入失败", 0, 0);
                    return;
                }
                emitRebuild("step", "重新装载各周期缓存…", 75, rows);
                engine.reloadCaches();
                emitRebuild("done", "本地库已重建 · 共 " + rows + " 根 K 线", 100, rows);
                emit("onData", "");       // 触发一次前端刷新（window.FF.onData）
            } catch (Exception e) {
                emitRebuild("error", Cfg.redact(brief(e)), 0, 0);
            } finally {
                engine.releaseDb();
            }
        });
    }

    /** onDbRebuild 回调：phase = start|step|done|error，pct 0..100，rows 行数。 */
    private void emitRebuild(String phase, String msg, int pct, int rows) {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("msg", Cfg.redact(msg == null ? "" : msg));
            o.put("pct", Math.max(0, Math.min(100, pct)));
            o.put("rows", rows);
        } catch (Exception ignored) {
        }
        emit("onDbRebuild", o.toString());
    }

    @JavascriptInterface
    public void clearForecasts() {
        store.clearForecasts();
        emit("onToast", q("预测记录已清空"));
    }

    /** 同步历史数据：最近 7 天分钟线体检 + 日线 180 天回溯，缺失/清空增量回补。 */
    @JavascriptInterface
    public void syncHistory() {
        syncHistory0(false);
    }

    /** 强制体检（仅补缺，不整段重写）。 */
    @JavascriptInterface
    public void syncHistoryForce(boolean force) {
        syncHistory0(force);
    }

    private void syncHistory0(final boolean force) {
        if (engine.isSyncing() || engine.isDbBusy()) {
            emit("onToast", q(engine.isDbBusy() && !engine.isSyncing()
                    ? "数据库忙：重建进行中，请稍候" : "同步正在进行中，请稍候"));
            return;
        }
        emit("onToast", q(force
                ? "强制体检（仅补缺）…（日线回溯 180 天）"
                : "体检最近 7 天并增量同步…（日线回溯 180 天）"));
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
            }, force);
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
            // Cfg.setLlmBase 校验失败 / seal() 加密失败都会抛异常：捕获并给友好提示，不崩
            try {
                res.put("ok", false);
                res.put("message", friendlySaveError(e));
            } catch (Exception ignored) {
            }
        }
        return res.toString();
    }

    private static String friendlySaveError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        m = Cfg.redact(m);
        if (m.contains("llm_base") || m.contains("HTTPS") || m.contains("明文") || m.contains("公网")) {
            return "保存失败：接口地址必须是公网 HTTPS，不能用明文 HTTP 或本机/内网地址";
        }
        if (m.contains("加密") || m.contains("密钥") || m.contains("安全设置")
                || m.contains("拒绝以明文")) {
            return "保存失败：密钥加密失败（请检查设备安全设置/锁屏），未以明文保存";
        }
        if (m.length() > 80) m = m.substring(0, 80) + "…";
        return "保存失败：" + m;
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
            main.post(() -> {
                if (closed) return;
                ((MainActivity) ctx).applyThemeUi();
            });
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
            long since = now - 10 * 60_000L;   // 与量化侧一致：10 分钟去重
            String overall = d.optString("action", joinReasons(d));
            for (int i = 0; i < hs.length(); i++) {
                JSONObject h = hs.optJSONObject(i);
                if (h == null) continue;
                String key = h.optString("key", "");
                String dir = h.optString("direction", "");
                if (key.isEmpty() || dir.isEmpty() || "—".equals(dir)) continue;
                // 避免刷爆 forecasts 与污染 hitStats
                if (store.hasRecentForecast(code, key, since)) continue;
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

    // ---------------- 服务端对照开关 ----------------

    /**
     * 保存服务端基址与开关。base 只允许：
     * http://127.0.0.1:PORT、http://localhost:PORT，或显式开启时（on=true）的
     * https 主机、私有网段 IPv4（10./172.16-31./192.168.）。
     * 拒绝公网 http、userinfo、路径穿越与非 https 基址；非法直接抛异常（不保存）。
     */
    @JavascriptInterface
    public void setServer(String base, boolean on) {
        SharedPreferences sp = prefs();
        String b = base == null ? "" : base.trim();
        if (!b.isEmpty()) {
            // 允许「只配置地址、不开对照开关」（数据库同步用），地址仍必须通过白名单校验
            if (!isValidServerBase(b)) {
                throw new IllegalArgumentException(
                        "服务端地址不合法：仅允许 http://127.0.0.1:PORT、http://localhost:PORT，"
                                + "或显式开启的 https 主机 / 私有网段 IPv4；禁止 userinfo 与路径穿越");
            }
            sp.edit().putString("server_base", b).apply();
        } else if (on) {
            throw new IllegalArgumentException("服务端地址不能为空");
        }
        sp.edit().putBoolean("server_on", on).apply();
    }

    /**
     * GET base+path，超时 5 秒，返回响应体；失败返回 {@code {"error":"<脱敏原因>"}}。
     * path 必须匹配 {@code ^/api/[A-Za-z0-9/_.?=&%\-]*$}。
     *
     * <p>v2.6.0 前置条件：只要求 server_base 已配置且合法，不再要求 server_on=true。
     * 对照开关（server_on）仅由前端 pull() 决定是否调用本方法（默认关）。
     */
    @JavascriptInterface
    public String serverGet(String path) {
        if (closed) return errJson("桥已关闭");
        if (path == null || !SERVER_PATH.matcher(path).matches()) {
            return errJson("非法的请求路径");
        }
        SharedPreferences sp = prefs();
        String base = sp.getString("server_base", "");
        if (base == null || base.isEmpty() || !isValidServerBase(base)) {
            return errJson("服务端地址未配置或不合法");
        }
        // 显式 Callable，避免 submit(Runnable) / submit(Callable<T>) 重载歧义
        java.util.concurrent.Callable<String> task = () -> doServerGet(base, path);
        Future<String> f;
        try {
            f = pool.submit(task);
        } catch (Exception e) {
            return errJson("桥已关闭");
        }
        try {
            return f.get(5, TimeUnit.SECONDS);   // 不阻塞超过 5 秒
        } catch (TimeoutException te) {
            f.cancel(true);
            return errJson("服务端请求超时（5 秒）");
        } catch (Exception e) {
            return errJson(Cfg.redact(brief(e)));
        }
    }

    /**
     * POST base+path，application/json，超时 10 秒，body ≤256KB；失败返回
     * {@code {"error":"<脱敏>"}}。前置条件与 serverGet 一致：只要求 server_base
     * 已配置且合法（不要求 server_on）；路径白名单与地址白名单完全复用。
     */
    @JavascriptInterface
    public String serverPost(String path, String body) {
        if (closed) return errJson("桥已关闭");
        if (path == null || !SERVER_PATH.matcher(path).matches()) {
            return errJson("非法的请求路径");
        }
        byte[] payload = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        if (payload.length > 256 * 1024) return errJson("请求体过大（超过 256KB）");
        SharedPreferences sp = prefs();
        String base = sp.getString("server_base", "");
        if (base == null || base.isEmpty() || !isValidServerBase(base)) {
            return errJson("服务端地址未配置或不合法");
        }
        java.util.concurrent.Callable<String> task = () -> doServerPost(base, path, body);
        Future<String> f;
        try {
            f = pool.submit(task);
        } catch (Exception e) {
            return errJson("桥已关闭");
        }
        try {
            return f.get(10, TimeUnit.SECONDS);   // 不阻塞超过 10 秒
        } catch (TimeoutException te) {
            f.cancel(true);
            return errJson("服务端请求超时（10 秒）");
        } catch (Exception e) {
            return errJson(Cfg.redact(brief(e)));
        }
    }

    private String doServerGet(String base, String path) {
        return doServer(base, path, "GET", null, 4500, 4500);
    }

    private String doServerPost(String base, String path, String body) {
        return doServer(base, path, "POST", body == null ? "" : body, 9500, 9500);
    }

    /** GET / POST 共用的连接配置与脱敏逻辑（v2.6.0）：POST 写 application/json 请求体。 */
    private String doServer(String base, String path, String method,
                            String body, int connectTimeout, int readTimeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout(readTimeout);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json, */*");
            if ("POST".equals(method)) {
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                c.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(out);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = drain(in);
            if (code >= 400) return errJson("HTTP " + code);
            return resp.isEmpty() ? "{}" : resp;
        } catch (Exception e) {
            return errJson(Cfg.redact(brief(e)));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean isValidServerBase(String base) {
        try {
            if (base == null) return false;
            String s = base.trim();
            if (s.contains("..") || s.contains("\\")) return false;   // 拒绝路径穿越
            URI u = new URI(s);
            String scheme = u.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            if (u.getUserInfo() != null) return false;                 // 拒绝 userinfo
            if (u.getQuery() != null || u.getFragment() != null) return false;
            String rp = u.getRawPath();
            if (rp != null && !rp.isEmpty() && !rp.equals("/")) return false;  // 基址不得带路径
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;
            String h = host.toLowerCase(Locale.US);
            int port = u.getPort();
            if (scheme.equals("http")) {
                boolean loopback = h.equals("127.0.0.1") || h.equals("localhost")
                        || h.endsWith(".localhost");
                boolean priv = isPrivateIPv4(h);
                if (!loopback && !priv) return false;   // 拒绝公网 http
                if (port <= 0 || port > 65535) return false;   // 必须显式 PORT
                return true;
            }
            // https：用户显式开启时允许合法主机（公网/私有均可）
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPrivateIPv4(String h) {
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            if (a == 10) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String errJson(String reason) {
        String r = Cfg.redact(reason == null ? "" : reason);
        if (r.length() > 120) r = r.substring(0, 120);
        try {
            return new JSONObject().put("error", r).toString();
        } catch (Exception e) {
            return "{\"error\":\"请求失败\"}";
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

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m.replaceAll("\\s+", " ").trim();
    }

    // ---------------- 更新 ----------------

    @JavascriptInterface
    public void checkUpdate() {
        pool.execute(() -> {
            Updater.Info info = new Updater(ctx, cfg).check(this::emitCheckProgress);
            // 缓存有可用安装包的更新，供 installUpdate / installCachedUpdate 复用
            pendingUpdate = (info.hasUpdate && info.apkUrl != null && !info.apkUrl.isEmpty())
                    ? info : null;
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

    /** 检查阶段的分阶段进度：pct=0 表示不确定态（还没读到发行版）。 */
    private void emitCheckProgress(String msg, int pct) {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", "check");
            o.put("msg", msg);
            o.put("pct", Math.max(0, Math.min(100, pct)));
            o.put("done", Math.max(0, pct));
            o.put("total", 100);
        } catch (Exception ignored) {
        }
        emit("onUpdateProgress", o.toString());
    }

    private static String stripNotes(String s) {
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    /**
     * 签名保留（JS 仍这样调），但 native 忽略 apkUrl/version/size 三个参数，
     * 只用 checkUpdate() 缓存的 pendingUpdate；无缓存时直接报失败。
     */
    @JavascriptInterface
    public void installUpdate(String apkUrl, String version, long size) {
        final Updater.Info info = pendingUpdate;
        pool.execute(() -> {
            if (info == null) {
                emitUpdateProgress("error", 0, 0, "没有待安装的更新，请先检查更新");
                return;
            }
            emitUpdateProgress("start", 0, info.size);
            Updater.Result r = new Updater(ctx, cfg).downloadAndInstall(info,
                    (done, total) -> emitUpdateProgress("downloading", done, total));
            handleInstallResult(r, info.size);
        });
    }

    /** need_permission 阶段「继续安装」：直接安装已下载且已校验的 APK，不重新下载。 */
    @JavascriptInterface
    public void installCachedUpdate() {
        final Updater.Info info = pendingUpdate;
        pool.execute(() -> {
            if (info == null) {
                emitUpdateProgress("error", 0, 0, "没有待安装的更新，请先检查更新");
                return;
            }
            Updater.Result r = new Updater(ctx, cfg).installCached();
            handleInstallResult(r, info.size);
        });
    }

    private void handleInstallResult(Updater.Result r, long size) {
        if (r == null) {
            emitUpdateProgress("error", 0, 0, "安装失败");
            return;
        }
        if (Updater.ST_DONE.equals(r.status)) {
            emitUpdateProgress("done", size, size);              // done 强制 pct=100
        } else if (Updater.ST_NEED_PERMISSION.equals(r.status)) {
            emitUpdateProgress("need_permission", size, size);   // 跳设置页，APK 已就绪
        } else {
            emitUpdateProgress("error", 0, 0, r.message);        // error 带 msg（已脱敏）
        }
    }

    private void emitUpdateProgress(String phase, long done, long total) {
        emitUpdateProgress(phase, done, total, "");
    }

    /** 下载/安装进度：phase = start / downloading / done / need_permission / error。 */
    private void emitUpdateProgress(String phase, long done, long total, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("done", done);
            o.put("total", total);
            int pct;
            if ("done".equals(phase) || "need_permission".equals(phase)) {
                pct = 100;   // 完成/已就绪强制 100，即使 size=0
            } else if (total > 0) {
                pct = (int) Math.min(100, Math.round(done * 100f / total));
            } else {
                pct = 0;
            }
            o.put("pct", pct);
            if (msg != null && !msg.isEmpty()) o.put("msg", msg);   // 前端 error 用 o.msg
        } catch (Exception ignored) {
        }
        emit("onUpdateProgress", o.toString());
    }

    @JavascriptInterface
    public void toast(String msg) {
        if (closed) return;
        main.post(() -> {
            if (closed) return;
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }
}
