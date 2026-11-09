package com.zpj.futures.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.zpj.futures.Cfg;
import com.zpj.futures.db.Bar;
import com.zpj.futures.db.Store;
import com.zpj.futures.quant.Forecaster;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手机端采集与计算引擎：定时取数 → 本地落库 → 重算预测 → 回填到期预测。
 * 完全不依赖电脑后端。
 */
public class Engine {

    public interface Listener {
        void onUpdate();
    }

    private final Context ctx;
    private final Cfg cfg;
    private final Store store;
    private final SinaClient sina = new SinaClient();
    private volatile ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Map<String, SinaClient.Quote> quotes = new ConcurrentHashMap<>();
    /** key = code|period，缓存最近若干根 K 线，避免每次读库。 */
    private final Map<String, List<Bar>> klineCache = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> forecasts = new ConcurrentHashMap<>();

    /** 支持的周期，顺序即设置页展示顺序。 */
    public static final String[] PERIODS = {"1m", "5m", "15m", "30m", "60m", "1d"};

    private volatile long lastTick = 0;
    private volatile long lastKline = 0;
    private volatile String lastError = "";
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile boolean started = false;

    public Engine(Context ctx, Cfg cfg) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
        this.store = Store.get(ctx);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void start() {
        if (started) return;
        started = true;
        // 先用库里的历史填充缓存，保证断网也有曲线
        reloadCaches();
        lastTick = store.lastTickTs("AL");
        lastKline = store.lastKlineTs("AL", "1m");
        schedule();
        // 首次启动只拉 1 分钟线与日线；长周期（5/15/30/60 分钟）靠预置库，
        // 过期时由 refreshLongPeriodsIfStale() 增量补齐，避免每次启动都跑一遍大请求
        refresh(false);
        autoSyncIfEmpty();
    }

    /** 本地库空（预置库没导入成功 / 首次安装）时，后台自动补一次历史数据。 */
    private void autoSyncIfEmpty() {
        worker.execute(() -> {
            try {
                if (store.countKline("AL", "1m") < 100 || store.countKline("AL", "1d") < 200) {
                    syncHistory(null);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 重新从 SQLite 装载各周期缓存（导入预置库或同步后调用）。 */
    public void reloadCaches() {
        for (String code : Products.CODES) {
            for (String period : PERIODS) {
                List<Bar> bars = store.klines(code, period, "1d".equals(period) ? 4000 : 1600);
                if (bars.isEmpty()) klineCache.remove(code + "|" + period);
                else klineCache.put(code + "|" + period, bars);
            }
        }
    }

    public void stop() {
        timer.shutdownNow();
        worker.shutdownNow();
        started = false;
    }

    /** 刷新间隔变更后重新排程（旧调度器必须整体替换）。 */
    public void reschedule() {
        ScheduledExecutorService old = timer;
        timer = Executors.newSingleThreadScheduledExecutor();
        if (old != null) old.shutdownNow();
        if (started) schedule();
    }

    private void schedule() {
        int sec = cfg.refreshSec();
        timer.scheduleWithFixedDelay(() -> refresh(false), sec, sec, TimeUnit.SECONDS);
    }

    public Map<String, SinaClient.Quote> quotes() {
        return quotes;
    }

    public Map<String, JSONObject> forecasts() {
        return forecasts;
    }

    public long lastTick() {
        return lastTick;
    }

    public String lastError() {
        return lastError;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public List<Bar> bars(String code, String period) {
        List<Bar> cached = klineCache.get(code + "|" + period);
        if (cached != null && !cached.isEmpty()) return cached;
        List<Bar> fromDb = store.klines(code, period, "1d".equals(period) ? 4000 : 1600);
        if (!fromDb.isEmpty()) klineCache.put(code + "|" + period, fromDb);
        return fromDb;
    }

    /** full=true 时强制重拉 K 线（手动刷新 / 首次启动）。 */
    public void refresh(boolean full) {
        if (!busy.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                doRefresh(full);
                lastError = "";
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                busy.set(false);
                if (listener != null) main.post(listener::onUpdate);
            }
        });
    }

    private void doRefresh(boolean full) throws Exception {
        // 1) 实时报价（一次请求拿三品种）
        Map<String, SinaClient.Quote> q = sina.realtime();
        for (SinaClient.Quote v : q.values()) {
            quotes.put(v.code, v);
            store.saveTick(v.code, v.ts, v.last, v.bid, v.ask, v.volume, v.oi);
        }
        if (!q.isEmpty()) lastTick = System.currentTimeMillis();

        // 2) K 线：首次/手动/超过 5 分钟才拉，节省流量
        boolean needKline = full || lastKline == 0
                || System.currentTimeMillis() - lastKline > 300_000L;
        if (needKline) {
            for (String code : Products.CODES) {
                try {
                    List<Bar> m = sina.minute(code);
                    if (!m.isEmpty()) {
                        store.upsertKlines(code, "1m", m);
                        klineCache.put(code + "|1m", store.klines(code, "1m", 1600));
                    }
                } catch (Exception ignored) {
                }
                try {
                    List<Bar> d = sina.daily(code);
                    if (!d.isEmpty()) {
                        store.upsertKlines(code, "1d", d);
                        klineCache.put(code + "|1d", store.klines(code, "1d", 4000));
                    }
                } catch (Exception ignored) {
                }
            }
            // 手动刷新时顺带更新 60 分钟线（约 180 天历史），自动刷新不拉，省流量
            if (full) refreshLongPeriods();
            lastKline = System.currentTimeMillis();
        }

        // 预置/上次同步的 60 分钟线过期时补一次（3 小时），保证长周期曲线不断
        refreshLongPeriodsIfStale();

        // 3) 重算预测
        for (String code : Products.CODES) {
            JSONObject f = Forecaster.forecast(code,
                    bars(code, "1m"), bars(code, "60m"), bars(code, "1d"));
            forecasts.put(code, f);
            saveForecasts(f);
        }

        // 4) 回填到期预测（用当前价验证方向）
        verifyDue();

        // 5) 清理 30 天前的秒级数据，避免无限膨胀
        store.prune(30);
    }

    /** 手动刷新时顺带更新 5/15/30/60 分钟线（伦铝源站只给当日分时，跳过）。 */
    private void refreshLongPeriods() {
        for (String code : Products.CODES) {
            if ("AHD".equals(code)) continue;
            for (String period : new String[]{"5m", "15m", "30m", "60m"}) {
                try {
                    List<Bar> bars = sina.minute(code, SinaClient.typeOf(period));
                    if (!bars.isEmpty()) {
                        store.upsertKlines(code, period, bars);
                        klineCache.put(code + "|" + period,
                                store.klines(code, period, 1600));
                    }
                    Thread.sleep(220);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 60 分钟线超过 3 小时未更新时补拉一次（只拉 60m，流量可控）。 */
    private void refreshLongPeriodsIfStale() {
        long now = System.currentTimeMillis();
        for (String code : Products.CODES) {
            if ("AHD".equals(code)) continue;
            if (now - store.lastKlineTs(code, "60m") < 3 * 3600_000L) continue;
            try {
                List<Bar> bars = sina.minute(code, 60);
                if (!bars.isEmpty()) {
                    store.upsertKlines(code, "60m", bars);
                    klineCache.put(code + "|60m", store.klines(code, "60m", 1600));
                }
                Thread.sleep(220);
            } catch (Exception ignored) {
            }
        }
    }

    public interface SyncListener {
        void onStep(String message);
    }

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public boolean isSyncing() {
        return syncing.get();
    }

    /**
     * 同步历史数据：与官方源做**增量**合并。
     * 日线取近 180 天窗口，分钟线取源站当前可给的最近 1023 根；
     * 只把「本地最后一根及之后」的 bar 写库（boundary 那根会被 REPLACE 刷新），
     * 因此重复点击不会重复写库，只会补上新的增量。
     */
    public JSONObject syncHistory(SyncListener cb) {
        JSONObject res = new JSONObject();
        if (!syncing.compareAndSet(false, true)) {
            try {
                res.put("ok", false);
                res.put("message", "同步正在进行中");
            } catch (Exception ignored) {
            }
            return res;
        }
        long now = System.currentTimeMillis();
        long cut = now - 180L * 86400_000L;
        int added = 0;
        StringBuilder fail = new StringBuilder();
        JSONObject detail = new JSONObject();
        try {
            for (String code : Products.CODES) {
                String name = Products.nameOf(code);
                JSONObject one = new JSONObject();
                String[] periods = "AHD".equals(code)
                        ? new String[]{"1d", "1m"}
                        : new String[]{"1d", "1m", "5m", "15m", "30m", "60m"};
                for (String period : periods) {
                    if (cb != null) cb.onStep("同步 " + name + " " + labelOf(period) + "…");
                    JSONObject r = syncOne(code, period, cut);
                    one.put(period, r);
                    added += r.optInt("added", 0);
                    if (r.has("error")) {
                        fail.append(name).append(labelOf(period))
                                .append("[").append(r.optString("error")).append("] ");
                    }
                    Thread.sleep("1d".equals(period) ? 220 : 260);
                }
                detail.put(code, one);
            }
            reloadCaches();
            cfg.setLastSync(now);
            JSONObject periods = store.periodStats();
            String msg = summary(periods, now, added);
            res.put("ok", true);
            res.put("fetched", added);
            res.put("periods", periods);
            res.put("detail", detail);
            res.put("message", msg);
            cfg.setLastSyncMsg(msg);
            if (fail.length() > 0) {
                String warn = "部分数据未取到：" + trimTo(fail.toString(), 220);
                res.put("warn", warn);
                cfg.setLastSyncMsg(msg + "｜" + warn);
            }
        } catch (Exception e) {
            try {
                String m = "同步失败：" + brief(e);
                res.put("ok", false);
                res.put("message", m);
                cfg.setLastSyncMsg(m);
            } catch (Exception ignored) {
            }
        } finally {
            syncing.set(false);
        }
        return res;
    }

    /** 单个 品种×周期 的增量同步；异常写进返回值而不是抛给上层。 */
    private JSONObject syncOne(String code, String period, long dailyCut) {
        JSONObject r = new JSONObject();
        try {
            List<Bar> bars = "1d".equals(period)
                    ? sina.daily(code)
                    : sina.minute(code, SinaClient.typeOf(period));
            if ("1d".equals(period)) {
                List<Bar> recent = new ArrayList<>();
                for (Bar b : bars) if (b.ts >= dailyCut) recent.add(b);
                bars = recent;
            }
            long localMax = store.lastKlineTs(code, period);
            int before = store.countKline(code, period);
            int incoming = 0;
            if (!bars.isEmpty()) {
                List<Bar> add = new ArrayList<>();
                for (Bar b : bars) if (localMax <= 0 || b.ts >= localMax) add.add(b);
                incoming = add.size();
                if (incoming > 0) store.upsertKlines(code, period, add);
            }
            int after = store.countKline(code, period);
            r.put("added", Math.max(0, after - before));
            r.put("total", after);
            r.put("fetched", bars.size());
            r.put("incoming", incoming);
            if (after <= 0) r.put("error", "返回 0 根");
        } catch (Exception e) {
            try {
                r.put("added", 0);
                r.put("error", brief(e));
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    private static String labelOf(String period) {
        switch (period) {
            case "1d":
                return "日线";
            case "60m":
                return "60分钟";
            case "30m":
                return "30分钟";
            case "15m":
                return "15分钟";
            case "5m":
                return "5分钟";
            default:
                return "1分钟";
        }
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return trimTo(m, 60);
    }

    private static String trimTo(String s, int n) {
        String v = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return v.length() > n ? v.substring(0, n) + "…" : v;
    }

    /** 把各周期覆盖情况拼成一句人话。 */
    private static String summary(JSONObject periods, long now, int added) {
        StringBuilder sb = new StringBuilder();
        sb.append("新增 ").append(added).append(" 根 · 本地 ");
        String[] keys = {"1d", "60m", "30m", "15m", "5m", "1m"};
        String[] labels = {"日线", "60分", "30分", "15分", "5分", "1分"};
        boolean first = true;
        for (int i = 0; i < keys.length; i++) {
            JSONObject it = periods.optJSONObject(keys[i]);
            if (it == null) continue;
            int n = it.optInt("n");
            if (n <= 0) continue;
            if (!first) sb.append(" · ");
            sb.append(labels[i]).append(" ").append(n);
            first = false;
        }
        if (first) sb.append("暂无数据（网络不可用？）");
        return sb.toString();
    }

    /** 每个品种每个周期 10 分钟内只留一条，避免预测表被刷爆。 */
    private void saveForecasts(JSONObject f) {
        String code = f.optString("code");
        JSONArray hs = f.optJSONArray("horizons");
        if (hs == null) return;
        long now = System.currentTimeMillis();
        long since = now - 10 * 60_000L;
        double score = f.optDouble("score", 0);
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h == null) continue;
            String key = h.optString("key");
            String dir = h.optString("direction");
            if ("数据不足".equals(dir)) continue;
            if (store.hasRecentForecast(code, key, since)) continue;
            String reason = h.optString("note", "");
            store.addForecast(code, key, dir, h.optDouble("p_up", 0.5),
                    h.optDouble("target_price", 0), f.optDouble("price", 0),
                    score, "quant", reason, now, Forecaster.dueAt(key));
        }
    }

    private void verifyDue() {
        JSONArray due = store.dueForecasts(System.currentTimeMillis());
        for (int i = 0; i < due.length(); i++) {
            JSONObject o = due.optJSONObject(i);
            if (o == null) continue;
            SinaClient.Quote q = quotes.get(o.optString("code"));
            if (q == null || q.last <= 0) continue;
            double base = o.optDouble("price", 0);
            if (base <= 0) continue;
            String dir = o.optString("dir");
            double chg = (q.last - base) / base;
            int hit;
            if ("上涨".equals(dir)) hit = chg > 0 ? 1 : 0;
            else if ("下跌".equals(dir)) hit = chg < 0 ? 1 : 0;
            else hit = Math.abs(chg) < 0.002 ? 1 : 0;
            store.fillActual(o.optLong("id"), q.last, hit);
        }
    }

    /** 供 JS 取得的完整状态。 */
    public JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            JSONArray ps = new JSONArray();
            for (int i = 0; i < Products.CODES.length; i++) {
                String code = Products.CODES[i];
                JSONObject p = new JSONObject();
                p.put("code", code);
                p.put("short", Products.SHORT[i]);
                p.put("name", Products.NAMES[i]);
                p.put("unit", Products.UNITS[i]);
                SinaClient.Quote q = quotes.get(code);
                if (q != null) {
                    JSONObject qj = new JSONObject();
                    qj.put("last", q.last);
                    qj.put("open", q.open);
                    qj.put("high", q.high);
                    qj.put("low", q.low);
                    qj.put("prev_close", q.prevClose);
                    qj.put("bid", q.bid);
                    qj.put("ask", q.ask);
                    qj.put("volume", q.volume);
                    qj.put("oi", q.oi);
                    double chg = q.prevClose > 0 ? (q.last - q.prevClose) / q.prevClose * 100 : 0;
                    qj.put("change_pct", Math.round(chg * 100) / 100.0);
                    qj.put("ts", q.ts);
                    p.put("quote", qj);
                }
                p.put("minute_bars", store.countKline(code, "1m"));
                p.put("daily_bars", store.countKline(code, "1d"));
                JSONObject f = forecasts.get(code);
                if (f != null) p.put("forecast", f);
                ps.put(p);
            }
            o.put("products", ps);
            o.put("last_tick", lastTick);
            o.put("last_error", lastError);
            o.put("busy", busy.get());
            o.put("refresh_sec", cfg.refreshSec());
            o.put("last_sync", cfg.lastSync());
            o.put("last_sync_msg", cfg.lastSyncMsg());
            o.put("seed_version", cfg.seedVersion());
            o.put("seed_imported_at", cfg.seedImportedAt());
            o.put("kline_rows", store.countKlineAll());
            o.put("syncing", syncing.get());
            o.put("stats", store.stats());
            o.put("horizons", new JSONArray());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 采样成前端画图用的点（控制体积）。 */
    public JSONArray sparkline(String code, int points) {
        JSONArray arr = new JSONArray();
        try {
            List<double[]> t = store.recentTicks(code, points * 4);
            if (t.isEmpty()) {
                List<Bar> bars = bars(code, "1m");
                int from = Math.max(0, bars.size() - points);
                for (int i = from; i < bars.size(); i++) {
                    JSONArray p = new JSONArray();
                    p.put(bars.get(i).ts);
                    p.put(bars.get(i).c);
                    arr.put(p);
                }
                return arr;
            }
            int step = Math.max(1, t.size() / points);
            for (int i = 0; i < t.size(); i += step) {
                JSONArray p = new JSONArray();
                p.put((long) t.get(i)[0]);
                p.put(t.get(i)[1]);
                arr.put(p);
            }
        } catch (Exception ignored) {
        }
        return arr;
    }
}
