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
import java.util.Set;
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

    /** 完整性体检窗口：最近 7 天。 */
    public static final int CHECK_DAYS = 7;

    /** 秒级快照只能本机采集，体检只看最近 30 分钟是否还在进点。 */
    private static final long TICK_WINDOW_MS = 30 * 60_000L;

    public JSONObject syncHistory(SyncListener cb) {
        return syncHistory(cb, false);
    }

    /**
     * 同步历史数据：**体检 → 补齐 → 增量** 三段（v2.4.0）。
     *
     * <p>体检把源站返回的序列当基准，逐根比对本地时间戳：源站有、本地没有 = 缺失；
     * 本地该周期近 7 天一根都没有而源站有 = 被清空。有缺失/清空就把该周期窗口内的 bar
     * 整体 REPLACE 写回（可回补历史空洞），没有缺失才走原来的 tail 增量。
     * 以源站序列为基准可以完全避开自建交易日历，夜盘、周末、节假日都不会误判。
     *
     * <p>源站单周期上限 1023 根（1m≈2 个交易日、5m≈9 天、15m≈27 天），
     * 因此 7 天的 1m 空洞可能补不全，返回值里如实给出「实际覆盖起点」。
     *
     * @param forceFull true 时忽略缺失判定，把近 7 天窗口整体重写（数据错乱时手动使用）
     */
    public JSONObject syncHistory(SyncListener cb, boolean forceFull) {
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
        long dailyCut = now - 180L * 86400_000L;
        long checkCut = now - CHECK_DAYS * 86400_000L;
        List<Job> jobs = new ArrayList<>();
        for (String code : Products.CODES) {
            for (String period : periodsOf(code)) jobs.add(new Job(code, period));
        }
        int totalSteps = jobs.size() * 2 + 2;
        int step = 0;
        int added = 0, scanned = 0, missing = 0, gaps = 0, cleared = 0;
        int healedItems = 0, healedBars = 0;
        StringBuilder fail = new StringBuilder();
        JSONObject detail = new JSONObject();
        JSONObject coverage = new JSONObject();
        try {
            // ---------- 第 1 段：体检（拉一次源站数据并缓存，写入阶段复用） ----------
            for (Job j : jobs) {
                if (cb != null) {
                    cb.onStep("体检 " + Products.nameOf(j.code) + " " + labelOf(j.period) + "…",
                            step, totalSteps);
                }
                try {
                    j.bars = "1d".equals(j.period)
                            ? within(sina.daily(j.code), dailyCut)
                            : sina.minute(j.code, SinaClient.typeOf(j.period));
                } catch (Exception e) {
                    j.error = brief(e);
                }
                if (j.bars == null) j.bars = new ArrayList<>();
                if (!j.bars.isEmpty()) {
                    analyze(j, checkCut);
                    scanned++;
                    missing += j.missing;
                    gaps += j.gaps;
                    if (j.cleared) cleared++;
                }
                step++;
                Thread.sleep("1d".equals(j.period) ? 160 : 220);
            }
            if (cb != null) {
                cb.onStep("体检完成 · 扫描 " + scanned + " 组 · 缺失 " + missing + " 根 / "
                        + gaps + " 段" + (cleared > 0 ? " · 清空 " + cleared + " 组" : ""),
                        step, totalSteps);
            }
            step++;

            // ---------- 第 2 段：补齐（有缺口）或增量（无缺口） ----------
            for (Job j : jobs) {
                String name = Products.nameOf(j.code);
                boolean heal = forceFull || j.missing > 0 || j.cleared;
                if (cb != null) {
                    cb.onStep((heal ? "补齐 " : "增量 ") + name + " " + labelOf(j.period)
                            + (j.missing > 0 ? "（缺 " + j.missing + " 根）" : "") + "…",
                            step, totalSteps);
                }
                JSONObject r = apply(j, forceFull);
                JSONObject one = detail.optJSONObject(j.code);
                if (one == null) {
                    one = new JSONObject();
                    detail.put(j.code, one);
                }
                one.put(j.period, r);
                added += r.optInt("added", 0);
                if (r.optBoolean("healed", false)) {
                    healedItems++;
                    healedBars += j.missing;
                }
                long cov = store.firstKlineTsSince(j.code, j.period, checkCut);
                coverage.put(j.code + "|" + j.period, cov);
                if (r.has("error")) {
                    fail.append(name).append(labelOf(j.period))
                            .append("[").append(r.optString("error")).append("] ");
                }
                step++;
                Thread.sleep("1d".equals(j.period) ? 160 : 200);
            }

            // ---------- 第 3 段：秒级校验 + 刷新缓存 + 统计 ----------
            if (cb != null) cb.onStep("校验秒级快照 · 刷新本地缓存…", totalSteps, totalSteps);
            JSONObject ticks = checkTicks();
            reloadCaches();
            cfg.setLastSync(now);

            JSONObject periods = store.periodStats();
            JSONObject check = new JSONObject();
            check.put("days", CHECK_DAYS);
            check.put("scanned", scanned);
            check.put("missing", missing);
            check.put("gaps", gaps);
            check.put("cleared", cleared);
            check.put("healed_items", healedItems);
            check.put("healed_bars", healedBars);
            check.put("forced", forceFull);
            check.put("incremental", added);

            String base = summary(periods, now, added);
            String head;
            if (forceFull) head = "强制全量重写近 " + CHECK_DAYS + " 天 · ";
            else if (missing > 0 || cleared > 0) {
                head = "体检回补 " + healedBars + " 根 / " + healedItems + " 组 · ";
            } else head = "体检无缺失 · ";
            String msg = head + base;

            long cov1m = minCoverage(coverage, "|1m");
            String note = "秒级仅本机采集（最近 30 分钟 " + ticks.optInt("points", 0)
                    + " 点），公开源不提供历史秒级，无法回溯补齐";
            if (cov1m > 0) {
                note += "；1 分钟线实际覆盖起点 " + dayStr(cov1m) + "（受源站 1023 根上限）";
            }

            res.put("ok", true);
            res.put("fetched", added);
            res.put("added", added);
            res.put("healed_bars", healedBars);
            res.put("healed_items", healedItems);
            res.put("periods", periods);
            res.put("detail", detail);
            res.put("check", check);
            res.put("ticks", ticks);
            res.put("coverage", coverage);
            res.put("note", note);
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

    /** 一次体检/写入的工作单元：一个「品种 × 周期」。 */
    private static final class Job {
        final String code;
        final String period;
        List<Bar> bars = new ArrayList<>();   // 源站返回的全部 bar
        List<Bar> window = new ArrayList<>(); // 落在最近 7 天内的 bar
        int localWindow;                      // 本地近 7 天根数
        int missing;                          // 空洞根数（不含正常尾部新增）
        int gaps;                             // 空洞连成几段
        int tail;                             // 本地最后一根之后的新 bar（正常增量）
        boolean cleared;                      // 本地近 7 天为 0 而源站有数据
        String error;

        Job(String code, String period) {
            this.code = code;
            this.period = period;
        }
    }

    /** 逐根比对源站与本地，算出「空洞」数（不含正常尾部新增）、缺口段数、是否整段被清空。 */
    private void analyze(Job j, long checkCut) {
        j.window = new ArrayList<>();
        for (Bar b : j.bars) if (b.ts >= checkCut) j.window.add(b);
        Set<Long> local = store.klineTsSet(j.code, j.period, checkCut);
        j.localWindow = local.size();
        j.cleared = j.localWindow == 0 && !j.window.isEmpty();
        if (j.cleared) {
            // 整段没了：全部按缺失算，一次整体写回
            j.missing = j.window.size();
            j.gaps = 1;
            j.tail = 0;
            return;
        }
        long localMax = 0;
        for (Long t : local) if (t > localMax) localMax = t;
        boolean inGap = false;
        for (Bar b : j.window) {
            if (local.contains(b.ts)) {
                inGap = false;
            } else if (localMax > 0 && b.ts > localMax) {
                // 本地最后一根之后的新 bar：正常增量，不算缺口
                j.tail++;
            } else {
                j.missing++;
                if (!inGap) {
                    j.gaps++;
                    inGap = true;
                }
            }
        }
    }

    /** 有空洞/清空就把近 7 天窗口整体 REPLACE 写回；只有尾部新增时按增量写。 */
    private JSONObject apply(Job j, boolean forceFull) {
        JSONObject r = new JSONObject();
        try {
            int before = store.countKline(j.code, j.period);
            long localMax = store.lastKlineTs(j.code, j.period);
            boolean heal = forceFull || j.missing > 0 || j.cleared;
            List<Bar> write;
            if (heal) write = j.window.isEmpty() ? j.bars : j.window;
            else write = tail(j.bars, localMax);
            if (!write.isEmpty()) store.upsertKlines(j.code, j.period, write);
            int after = store.countKline(j.code, j.period);
            r.put("added", Math.max(0, after - before));
            r.put("total", after);
            r.put("fetched", j.bars.size());
            r.put("written", write.size());
            r.put("local_window", j.localWindow);
            r.put("tail", j.tail);
            r.put("missing", j.missing);
            r.put("gaps", j.gaps);
            r.put("cleared", j.cleared);
            r.put("healed", heal);
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

    /** 秒级体检：统计最近 30 分钟点数；最新点太旧就立刻补一次实时报价。 */
    private JSONObject checkTicks() {
        JSONObject o = new JSONObject();
        long now = System.currentTimeMillis();
        long since = now - TICK_WINDOW_MS;
        try {
            int pts = 0;
            long newest = 0;
            for (String code : Products.CODES) {
                pts += store.countTicksSince(code, since);
                newest = Math.max(newest, store.lastTickTs(code));
            }
            long staleMs = Math.max(180_000L, cfg.refreshSec() * 2000L);
            boolean refilled = false;
            if (newest == 0 || now - newest > staleMs) {
                Map<String, SinaClient.Quote> q = sina.realtime();
                for (SinaClient.Quote v : q.values()) {
                    quotes.put(v.code, v);
                    store.saveTick(v.code, v.ts, v.last, v.bid, v.ask, v.volume, v.oi);
                    newest = Math.max(newest, v.ts);
                }
                refilled = !q.isEmpty();
            }
            o.put("points", pts);
            o.put("newest", newest);
            o.put("age_sec", newest > 0 ? Math.max(0, (now - newest) / 1000) : -1);
            o.put("refilled", refilled);
        } catch (Exception e) {
            try {
                o.put("points", 0);
                o.put("error", brief(e));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    /** 只保留 ts >= cut 的部分。 */
    private static List<Bar> within(List<Bar> bars, long cut) {
        List<Bar> out = new ArrayList<>();
        if (bars == null) return out;
        for (Bar b : bars) if (b.ts >= cut) out.add(b);
        return out;
    }

    /** 从 coverage 里取某个周期的多品种最早覆盖时间（key 形如 "AL|1m"）。 */
    private static long minCoverage(JSONObject coverage, String suffix) {
        long min = 0;
        java.util.Iterator<String> it = coverage.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!k.endsWith(suffix)) continue;
            long v = coverage.optLong(k, 0);
            if (v > 0 && (min == 0 || v < min)) min = v;
        }
        return min;
    }

    private static String dayStr(long ts) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(ts));
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
