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
    private volatile long lastMinuteSync = 0;
    private volatile long lastDailySync = 0;
    private volatile long lastForecastAt = 0;
    private volatile long nextForecastAt = 0;
    private volatile String lastError = "";
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean forecastBusy = new AtomicBoolean(false);
    private volatile ScheduledExecutorService predTimer = Executors.newSingleThreadScheduledExecutor();
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
        scheduleForecast();
        // 首屏先出结论（用库里已有数据），随后采集线程再增量补
        computeForecast(false);
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
        predTimer.shutdownNow();
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

    /**
     * 预测调度：对齐到每个整分钟（:00 秒）跑一次。
     * 整分只重算 ≤30 分钟的四档；整点额外增量补 1m/日线并跑全七档。
     */
    private void scheduleForecast() {
        long now = System.currentTimeMillis();
        long delay = 60_000 - (now % 60_000) + 300;  // 留 300ms 容错，避免落在 :59.99
        nextForecastAt = now + delay;
        try {
            predTimer.scheduleAtFixedRate(this::tickForecast, delay, 60_000, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private void tickForecast() {
        if (!forecastBusy.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        boolean wholeHour = (now / 60_000) % 60 == 0;
        nextForecastAt = now + 60_000;
        try {
            if (wholeHour) {
                // 整点加强：先把数据补到最新，再跑全七档
                incrementalMinute(true);
                incrementalDaily(true);
                refreshLongPeriodsIfStale();
                computeForecast(true);
            } else {
                computeForecast(false);
            }
            verifyDue();
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            forecastBusy.set(false);
            if (listener != null) main.post(listener::onUpdate);
        }
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

        // 2) 分钟线增量：每 60 秒一次，只补「本地最后一根及之后」
        incrementalMinute(full);
        // 3) 日线增量：每 30 分钟一次
        incrementalDaily(full);
        // 4) 60 分钟线过期时兜底（预置库很旧 / 长时间没开应用）
        refreshLongPeriodsIfStale();

        // 5) 回填到期预测（用当前价验证方向）
        verifyDue();

        // 6) 清理 30 天前的秒级数据，避免无限膨胀
        store.prune(30);

        // 7) 预测不在这里重算：整分钟对齐由 scheduleForecast() 负责；
        //    只有还没有任何结论时（首启 / 换品种）补一次，避免首屏空白
        if (forecasts.isEmpty()) computeForecast(false);
    }

    /**
     * 1 分钟线增量：只把「本地最后一根及之后」的 bar 写库，
     * 并用本次拿到的 1m 序列按时间桶聚合，增量补 5/15/30/60 分钟。
     *
     * @param force true 时忽略 60 秒节流（整点加强 / 手动刷新）
     * @return 实际写入的根数（含本地聚合产物）
     */
    public synchronized int incrementalMinute(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastMinuteSync < 60_000L) return 0;
        lastMinuteSync = now;
        int added = 0;
        for (String code : Products.CODES) {
            List<Bar> m;
            try {
                m = sina.minute(code, 1);
            } catch (Exception e) {
                continue;
            }
            if (m.isEmpty()) continue;
            long localMax = store.lastKlineTs(code, "1m");
            List<Bar> fresh = tail(m, localMax);
            if (!fresh.isEmpty()) {
                store.upsertKlines(code, "1m", fresh);
                mergeCache(code, "1m", fresh);
                added += fresh.size();
            }
            // 伦铝源站只给当日分时，聚合长周期没有意义，保持「1m + 日线」
            if ("AHD".equals(code)) continue;
            for (String period : new String[]{"5m", "15m", "30m", "60m"}) {
                List<Bar> agg = Forecaster.aggregateByTime(m, stepMinutes(period));
                if (agg.isEmpty()) continue;
                List<Bar> add = tail(agg, store.lastKlineTs(code, period));
                if (add.isEmpty()) continue;
                store.upsertKlines(code, period, add);
                mergeCache(code, period, add);
                added += add.size();
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
        }
        if (added > 0) lastKline = now;
        return added;
    }

    /** 日线增量：每 30 分钟一次，只写本地最后一根及之后。 */
    public synchronized int incrementalDaily(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastDailySync < 30 * 60_000L) return 0;
        lastDailySync = now;
        int added = 0;
        for (String code : Products.CODES) {
            try {
                List<Bar> d = sina.daily(code);
                if (d.isEmpty()) continue;
                List<Bar> add = tail(d, store.lastKlineTs(code, "1d"));
                if (!add.isEmpty()) {
                    store.upsertKlines(code, "1d", add);
                    mergeCache(code, "1d", add);
                    added += add.size();
                }
                Thread.sleep(160);
            } catch (Exception ignored) {
            }
        }
        return added;
    }

    /** 只保留 ts >= localMax 的部分（localMax<=0 表示本地为空，全要）。 */
    private static List<Bar> tail(List<Bar> bars, long localMax) {
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars) if (localMax <= 0 || b.ts >= localMax) out.add(b);
        return out;
    }

    /** 把增量 bar 并入内存缓存（同 ts 覆盖、尾部追加），不整表重读。 */
    private void mergeCache(String code, String period, List<Bar> fresh) {
        String key = code + "|" + period;
        List<Bar> cur = klineCache.get(key);
        if (cur == null || cur.isEmpty()) return;   // 未缓存：下次读库自然带上
        int limit = "1d".equals(period) ? 4000 : 1600;
        List<Bar> merged = new ArrayList<>(cur);
        for (Bar b : fresh) {
            Bar last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last == null || b.ts > last.ts) merged.add(b);
            else if (b.ts == last.ts) merged.set(merged.size() - 1, b);
        }
        while (merged.size() > limit) merged.remove(0);
        klineCache.put(key, merged);
    }

    private static int stepMinutes(String period) {
        switch (period) {
            case "60m":
                return 60;
            case "30m":
                return 30;
            case "15m":
                return 15;
            case "5m":
                return 5;
            default:
                return 1;
        }
    }

    /**
     * 重算预测。full=true 跑全部七档；false 只重算 ≤30 分钟的四档
     * （4h/8h/1d 沿用上一次结果，整点加强时才全部重算）。
     */
    public synchronized JSONObject computeForecast(boolean full) {
        for (String code : Products.CODES) {
            JSONObject prev = forecasts.get(code);
            JSONObject f = Forecaster.forecast(code, bars(code, "1m"),
                    bars(code, "60m"), bars(code, "1d"), prev, !full);
            forecasts.put(code, f);
            saveForecasts(f);
        }
        lastForecastAt = System.currentTimeMillis();
        return forecasts.get(Products.CODES[0]);
    }

    /** 手动刷新预测：先把数据增量补到最新，再跑全七档。后台线程调用。 */
    public JSONObject forceForecast(SyncListener cb) {
        JSONObject res = new JSONObject();
        if (!forecastBusy.compareAndSet(false, true)) {
            try {
                res.put("ok", false);
                res.put("message", "预测正在刷新中，请稍候");
            } catch (Exception ignored) {
            }
            return res;
        }
        try {
            if (cb != null) cb.onStep("补齐最近行情（1 分钟线 + 日线）…", 0, 3);
            int addMin = incrementalMinute(true);
            int addDay = incrementalDaily(true);
            refreshLongPeriodsIfStale();
            if (cb != null) cb.onStep("重算三品种七档方向…", 1, 3);
            computeForecast(true);
            if (cb != null) cb.onStep("回填到期预测…", 2, 3);
            verifyDue();
            int total = addMin + addDay;
            res.put("ok", true);
            res.put("added_minute", addMin);
            res.put("added_daily", addDay);
            res.put("forecast_at", lastForecastAt);
            res.put("message", "已更新：" + (total > 0 ? "新增 " + total + " 根数据 · " : "")
                    + "七档方向已重算");
            nextForecastAt = (System.currentTimeMillis() / 60_000 + 1) * 60_000 + 300;
        } catch (Exception e) {
            try {
                res.put("ok", false);
                res.put("message", "刷新失败：" + brief(e));
            } catch (Exception ignored) {
            }
        } finally {
            forecastBusy.set(false);
            if (listener != null) main.post(listener::onUpdate);
        }
        return res;
    }

    public long lastForecastAt() {
        return lastForecastAt;
    }

    public long nextForecastAt() {
        return nextForecastAt;
    }

    public boolean isForecastBusy() {
        return forecastBusy.get();
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
        /**
         * @param message 当前步骤文案
         * @param done    已完成的「品种 × 周期」步数（从 0 开始）
         * @param total   总步数
         */
        void onStep(String message, int done, int total);
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
        int step = 0;
        int totalSteps = 0;
        for (String code : Products.CODES) totalSteps += periodsOf(code).length;
        StringBuilder fail = new StringBuilder();
        JSONObject detail = new JSONObject();
        try {
            for (String code : Products.CODES) {
                String name = Products.nameOf(code);
                JSONObject one = new JSONObject();
                String[] periods = periodsOf(code);
                for (String period : periods) {
                    if (cb != null) {
                        cb.onStep("同步 " + name + " " + labelOf(period) + "…", step, totalSteps);
                    }
                    JSONObject r = syncOne(code, period, cut);
                    one.put(period, r);
                    added += r.optInt("added", 0);
                    if (r.has("error")) {
                        fail.append(name).append(labelOf(period))
                                .append("[").append(r.optString("error")).append("] ");
                    }
                    step++;
                    Thread.sleep("1d".equals(period) ? 220 : 260);
                }
                detail.put(code, one);
            }
            if (cb != null) cb.onStep("写入本地库并刷新…", totalSteps, totalSteps);
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

    /** 各品种要同步的周期；伦铝（外盘）只有日线与 1 分钟分时。 */
    private static String[] periodsOf(String code) {
        return "AHD".equals(code)
                ? new String[]{"1d", "1m"}
                : new String[]{"1d", "1m", "5m", "15m", "30m", "60m"};
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
            o.put("last_forecast", lastForecastAt);
            o.put("next_forecast", nextForecastAt);
            o.put("forecast_busy", forecastBusy.get());
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
