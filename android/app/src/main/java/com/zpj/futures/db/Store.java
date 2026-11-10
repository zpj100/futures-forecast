package com.zpj.futures.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 手机端本地库：秒级快照 / K 线 / 预测留档。
 * 全部为「手机内存储」，不依赖任何后端。
 */
public class Store extends SQLiteOpenHelper {

    private static final String NAME = "futures.db";
    private static final int VERSION = 1;
    private static Store inst;

    /** 应用级 Context：迁移 / 诊断需要从 assets 复制 seed.db。 */
    private final Context appCtx;

    // ---------------- v2.6.0 旧口径迁移 / 本地库诊断 ----------------

    /** 旧口径迁移只处理这四个周期；1m / 1d 不动（1m 本地可能有比 seed 更新的数据）。 */
    private static final String LEGACY_IN = "'5m','15m','30m','60m'";

    /**
     * 旧口径残留判定：ts 落在 seed 该组合 [min,max] 区间内、且不在 seed ts 集合。
     * seed_ts / seed_bounds 为临时表，用 NOT IN 子查询（不拼接超长 IN，
     * 也不依赖 SQLite 3.15+ 才支持的 (a,b,c) NOT IN 行值写法）。
     */
    private static final String LEGACY_WHERE =
            "period IN (" + LEGACY_IN + ")"
                    + " AND EXISTS (SELECT 1 FROM seed_bounds b WHERE b.code=kline.code"
                    + " AND b.period=kline.period AND kline.ts BETWEEN b.lo AND b.hi)"
                    + " AND (code||'|'||period||'|'||ts) NOT IN"
                    + " (SELECT k FROM seed_ts WHERE k IS NOT NULL)";

    /** 串行化临时表的建/查/删，避免迁移与诊断并发踩同一连接上的临时表。 */
    private final Object legacyLock = new Object();
    /** 进程级缓存：从 assets/seed.db 解析出的 (code,period) -> ts 集合与 [min,max]。 */
    private static volatile SeedIndex seedIndex = null;
    /** countLegacyBars 结果缓存，避免每次全表扫；写库方法会清除。 */
    private volatile JSONObject legacyCache = null;

    private static final class SeedIndex {
        final Map<String, long[]> bounds = new HashMap<>();   // "code|period" -> {min,max}
        final Map<String, Set<Long>> ts = new HashMap<>();    // "code|period" -> ts 集合
    }

    public static synchronized Store get(Context ctx) {
        if (inst == null) inst = new Store(ctx.getApplicationContext());
        return inst;
    }

    private Store(Context ctx) {
        super(ctx, NAME, null, VERSION);
        this.appCtx = ctx.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ticks(code TEXT NOT NULL, ts INTEGER NOT NULL,"
                + " price REAL, bid REAL, ask REAL, vol REAL, oi REAL,"
                + " PRIMARY KEY(code, ts))");
        db.execSQL("CREATE TABLE kline(code TEXT NOT NULL, period TEXT NOT NULL,"
                + " ts INTEGER NOT NULL, o REAL, h REAL, l REAL, c REAL, v REAL,"
                + " PRIMARY KEY(code, period, ts))");
        db.execSQL("CREATE TABLE forecasts(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " code TEXT, horizon TEXT, dir TEXT, p_up REAL, target REAL,"
                + " price REAL, score REAL, source TEXT, reason TEXT,"
                + " created_at INTEGER, due_at INTEGER, actual REAL, hit INTEGER)");
        db.execSQL("CREATE INDEX idx_fc_created ON forecasts(created_at DESC)");
        db.execSQL("CREATE INDEX idx_kline_q ON kline(code, period, ts DESC)");
        db.execSQL("CREATE INDEX idx_ticks_q ON ticks(code, ts DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 结构只增不改，旧库保留数据
        if (oldV < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_fc_created ON forecasts(created_at DESC)");
        }
    }

    // ---------------- ticks ----------------

    public void saveTick(String code, long ts, double price, double bid,
                         double ask, double vol, double oi) {
        ContentValues cv = new ContentValues();
        cv.put("code", code);
        cv.put("ts", ts);
        cv.put("price", price);
        cv.put("bid", bid);
        cv.put("ask", ask);
        cv.put("vol", vol);
        cv.put("oi", oi);
        getWritableDatabase().insertWithOnConflict(
                "ticks", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public long lastTickTs(String code) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(ts) FROM ticks WHERE code=?", new String[]{code});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    /** 返回最近 limit 条快照，按时间升序。元素为 [ts, price]。 */
    public List<double[]> recentTicks(String code, int limit) {
        List<double[]> out = new ArrayList<>();
        int lim = Math.max(1, Math.min(5000, limit));
        // 先按 DESC 取最近 lim 条，外层 ASC 排回时间正序；直接尾部追加，避免 out.add(0,..) 的 O(n²)
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, price FROM (SELECT ts, price FROM ticks WHERE code=?"
                        + " ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
                new String[]{code, String.valueOf(lim)});
        try {
            while (c.moveToNext()) out.add(new double[]{c.getLong(0), c.getDouble(1)});
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * 秒级曲线数据：把 ticks 按时间桶聚合成 OHLC。
     * bucketSec<=0 表示不聚合，直接返回原始采集点（每点 o=h=l=c=price）。
     * 返回按时间升序，元素为 [ts, o, h, l, c, n]；ts 取桶起点，保证时间轴稳定。
     *
     * <p>秒级数据只能来自本机采集（公开接口不提供秒级历史），
     * 采集频率由「自动刷新间隔」决定，因此 1s 视图在默认 30s 间隔下
     * 实际是 30 秒一个点，界面必须如实说明。
     */
    public List<double[]> tickSeries(String code, int bucketSec, int limit) {
        // 先 clamp：防负 LIMIT、防 bucketSec*1000L*max 溢出 long、防聚合分支无界读取
        int bsec = Math.max(0, Math.min(3600, bucketSec));
        int lim = Math.max(1, Math.min(5000, limit));
        int max = Math.max(2, lim);
        List<double[]> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        long window = bsec > 0 ? (long) bsec * 1000L * max * 2L : 86400_000L;
        // 原始点上限（聚合后桶数远小于点数）：两个分支共用同一条带 LIMIT 的 SQL
        long rawCap = max * 50L;
        String sql = "SELECT ts, price FROM (SELECT ts, price FROM ticks WHERE code=? AND ts>=?"
                + " ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC";
        Cursor c = getReadableDatabase().rawQuery(sql,
                new String[]{code, String.valueOf(now - window), String.valueOf(rawCap)});
        try {
            double curO = 0, curH = 0, curL = 0, curC = 0;
            long curTs = 0;
            int cnt = 0;
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                double p = c.getDouble(1);
                long b = bsec > 0 ? (ts / (bsec * 1000L)) * (bsec * 1000L) : ts;
                if (cnt > 0 && b != curTs) {
                    out.add(new double[]{curTs, curO, curH, curL, curC, cnt});
                    cnt = 0;
                }
                if (cnt == 0) {
                    curTs = b;
                    curO = curH = curL = curC = p;
                } else {
                    curH = Math.max(curH, p);
                    curL = Math.min(curL, p);
                    curC = p;
                }
                cnt++;
            }
            if (cnt > 0) out.add(new double[]{curTs, curO, curH, curL, curC, cnt});
        } finally {
            c.close();
        }
        // 只保留尾部 limit 个桶
        if (out.size() > max) return new ArrayList<>(out.subList(out.size() - max, out.size()));
        return out;
    }

    // ---------------- kline ----------------

    public void upsertKlines(String code, String period, List<Bar> bars) {
        if (bars == null || bars.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Bar b : bars) {
                ContentValues cv = new ContentValues();
                cv.put("code", code);
                cv.put("period", period);
                cv.put("ts", b.ts);
                cv.put("o", b.o);
                cv.put("h", b.h);
                cv.put("l", b.l);
                cv.put("c", b.c);
                cv.put("v", b.v);
                db.insertWithOnConflict("kline", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        invalidateLegacyCache();
    }

    public List<Bar> klines(String code, String period, int limit) {
        List<Bar> out = new ArrayList<>();
        int lim = Math.max(1, Math.min(5000, limit));
        // DESC 取最近 lim 根，外层 ASC 排回时间正序；直接尾部追加，消除头插 O(n²)
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,o,h,l,c,v FROM (SELECT ts,o,h,l,c,v FROM kline"
                        + " WHERE code=? AND period=? ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
                new String[]{code, period, String.valueOf(lim)});
        try {
            while (c.moveToNext()) {
                out.add(new Bar(c.getLong(0), c.getDouble(1), c.getDouble(2),
                        c.getDouble(3), c.getDouble(4), c.getDouble(5)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public long lastKlineTs(String code, String period) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(ts) FROM kline WHERE code=? AND period=?",
                new String[]{code, period});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    public int countKline(String code, String period) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM kline WHERE code=? AND period=?",
                new String[]{code, period});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** kline 表总行数（判断预置库是否需要导入）。 */
    public int countKlineAll() {
        return scalar("SELECT COUNT(*) FROM kline");
    }

    // ---------------- 体检辅助（同步前完整性检查用） ----------------

    /** 某周期在 sinceMs 之后本地有多少根（参数化查询，杜绝拼接注入）。 */
    public int countKlineSince(String code, String period, long sinceMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM kline WHERE code=? AND period=? AND ts>=?",
                new String[]{code, period, String.valueOf(sinceMs)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** sinceMs 之后本地已有的全部时间戳（用于和源站逐根比对，找出空洞）。 */
    public Set<Long> klineTsSet(String code, String period, long sinceMs) {
        Set<Long> out = new HashSet<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts FROM kline WHERE code=? AND period=? AND ts>=?",
                new String[]{code, period, String.valueOf(sinceMs)});
        try {
            while (c.moveToNext()) out.add(c.getLong(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** sinceMs 之后本地最早那根的时间；没有则 0。用于报告「实际覆盖起点」。 */
    public long firstKlineTsSince(String code, String period, long sinceMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MIN(ts) FROM kline WHERE code=? AND period=? AND ts>=?",
                new String[]{code, period, String.valueOf(sinceMs)});
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    /** 秒级快照在 sinceMs 之后的点数。 */
    public int countTicksSince(String code, long sinceMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM ticks WHERE code=? AND ts>=?",
                new String[]{code, String.valueOf(sinceMs)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    // ---------------- forecasts ----------------

    public long addForecast(String code, String horizon, String dir, double pUp,
                            double target, double price, double score, String source,
                            String reason, long createdAt, long dueAt) {
        ContentValues cv = new ContentValues();
        cv.put("code", code);
        cv.put("horizon", horizon);
        cv.put("dir", dir);
        cv.put("p_up", pUp);
        cv.put("target", target);
        cv.put("price", price);
        cv.put("score", score);
        cv.put("source", source);
        cv.put("reason", reason);
        cv.put("created_at", createdAt);
        cv.put("due_at", dueAt);
        return getWritableDatabase().insert("forecasts", null, cv);
    }

    /** 同一品种同一周期在 nearMs 内已有记录则不重复落库。 */
    public boolean hasRecentForecast(String code, String horizon, long sinceMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM forecasts WHERE code=? AND horizon=? AND created_at>=? LIMIT 1",
                new String[]{code, horizon, String.valueOf(sinceMs)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** 待回填实际价的记录：已过 due_at 且尚未回填。 */
    public JSONArray dueForecasts(long nowMs) {
        JSONArray arr = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,code,horizon,dir,price,due_at FROM forecasts"
                        + " WHERE hit IS NULL AND due_at<=? ORDER BY due_at ASC LIMIT 60",
                new String[]{String.valueOf(nowMs)});
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("code", c.getString(1));
                o.put("horizon", c.getString(2));
                o.put("dir", c.getString(3));
                o.put("price", c.getDouble(4));
                o.put("due_at", c.getLong(5));
                arr.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return arr;
    }

    public void fillActual(long id, double actual, int hit) {
        ContentValues cv = new ContentValues();
        cv.put("actual", actual);
        cv.put("hit", hit);
        getWritableDatabase().update("forecasts", cv, "id=?",
                new String[]{String.valueOf(id)});
    }

    public JSONArray listForecasts(int limit, String code) {
        String sql = "SELECT id,code,horizon,dir,p_up,target,price,score,source,reason,"
                + " created_at,due_at,actual,hit FROM forecasts";
        String[] args;
        if (code != null && !code.isEmpty()) {
            sql += " WHERE code=?";
            args = new String[]{code, String.valueOf(limit)};
        } else {
            args = new String[]{String.valueOf(limit)};
        }
        sql += " ORDER BY created_at DESC LIMIT ?";
        JSONArray arr = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("code", c.getString(1));
                o.put("horizon", c.getString(2));
                o.put("dir", c.getString(3));
                o.put("p_up", c.getDouble(4));
                o.put("target", c.getDouble(5));
                o.put("price", c.getDouble(6));
                o.put("score", c.getDouble(7));
                o.put("source", c.getString(8));
                o.put("reason", c.isNull(9) ? "" : c.getString(9));
                o.put("created_at", c.getLong(10));
                o.put("due_at", c.getLong(11));
                o.put("actual", c.isNull(12) ? JSONObject.NULL : c.getDouble(12));
                o.put("hit", c.isNull(13) ? JSONObject.NULL : c.getInt(13));
                arr.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return arr;
    }

    public JSONObject hitStats() {
        JSONObject o = new JSONObject();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), SUM(hit), SUM(CASE WHEN hit=0 THEN 1 ELSE 0 END)"
                        + " FROM forecasts WHERE hit IS NOT NULL", null);
        try {
            int n = c.moveToFirst() ? c.getInt(0) : 0;
            int hit = c.isNull(1) ? 0 : c.getInt(1);
            o.put("verified", n);
            o.put("hit", hit);
            o.put("miss", n - hit);
            o.put("rate", n > 0 ? Math.round(hit * 1000.0 / n) / 1000.0 : 0);
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return o;
    }

    public JSONObject stats() {
        JSONObject o = new JSONObject();
        try {
            o.put("ticks", scalar("SELECT COUNT(*) FROM ticks"));
            o.put("minute", scalar("SELECT COUNT(*) FROM kline WHERE period='1m'"));
            o.put("daily", scalar("SELECT COUNT(*) FROM kline WHERE period='1d'"));
            o.put("forecasts", scalar("SELECT COUNT(*) FROM forecasts"));
            o.put("periods", periodStats());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 各周期根数与最早一根的时间：{ "60m": {"n":1023,"from":1700000000000}, ... } */
    public JSONObject periodStats() {
        JSONObject o = new JSONObject();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT period, COUNT(*), MIN(ts) FROM kline GROUP BY period", null);
        try {
            while (c.moveToNext()) {
                JSONObject it = new JSONObject();
                it.put("n", c.getInt(1));
                it.put("from", c.getLong(2));
                o.put(c.getString(0), it);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return o;
    }

    /** 删除某个周期全部 K 线（预置库重建时用）。 */
    public int clearKline(String period) {
        int n = getWritableDatabase().delete("kline", "period=?",
                new String[]{period});
        invalidateLegacyCache();
        return n;
    }

    /** 清空全部 K 线（保留 ticks / forecasts），rebuildLocalDb 重建用。 */
    public int clearKline() {
        int n = getWritableDatabase().delete("kline", null, null);
        invalidateLegacyCache();
        return n;
    }

    private int scalar(String sql) {
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void prune(int keepDays) {
        long cut = System.currentTimeMillis() - keepDays * 86400_000L;
        getWritableDatabase().delete("ticks", "ts<?", new String[]{String.valueOf(cut)});
        getWritableDatabase().delete("kline", "period='1m' AND ts<?",
                new String[]{String.valueOf(cut)});
    }

    public void clearForecasts() {
        getWritableDatabase().delete("forecasts", null, null);
    }

    // ---------------- 本地库诊断（localDbStatus / exportBars / importBars 用） ----------------

    /**
     * 一次查询返回每 (code,period) 的 count/min/max：
     * {"AL|1m":{"n":1040,"min":<ms>,"max":<ms>}, ...}。
     */
    public JSONObject klineStats() {
        JSONObject o = new JSONObject();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT code,period,COUNT(*),MIN(ts),MAX(ts) FROM kline GROUP BY code,period",
                null);
        try {
            while (c.moveToNext()) {
                JSONObject it = new JSONObject();
                it.put("n", c.getInt(2));
                it.put("min", c.isNull(3) ? 0L : c.getLong(3));
                it.put("max", c.isNull(4) ? 0L : c.getLong(4));
                o.put(c.getString(0) + "|" + c.getString(1), it);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return o;
    }

    /** 秒级快照总体 n/min/max：{"n":..,"min":..,"max":..}。 */
    public JSONObject tickStats() {
        JSONObject o = new JSONObject();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*),MIN(ts),MAX(ts) FROM ticks", null);
        try {
            if (c.moveToFirst()) {
                o.put("n", c.getInt(0));
                o.put("min", c.isNull(1) ? 0L : c.getLong(1));
                o.put("max", c.isNull(2) ? 0L : c.getLong(2));
            } else {
                o.put("n", 0);
                o.put("min", 0);
                o.put("max", 0);
            }
        } catch (Exception e) {
            try {
                o.put("n", 0);
                o.put("min", 0);
                o.put("max", 0);
            } catch (Exception ignored) {
            }
        } finally {
            c.close();
        }
        return o;
    }

    /** ts 严格大于 since、升序、最多 limit 根（导出分页用；与后端 _paginate 口径一致）。 */
    public List<Bar> klinesSince(String code, String period, long since, int limit) {
        List<Bar> out = new ArrayList<>();
        int lim = Math.max(1, Math.min(5000, limit));
        long s = Math.max(0L, since);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,o,h,l,c,v FROM kline WHERE code=? AND period=? AND ts>?"
                        + " ORDER BY ts ASC LIMIT ?",
                new String[]{code, period, String.valueOf(s), String.valueOf(lim)});
        try {
            while (c.moveToNext()) {
                out.add(new Bar(c.getLong(0), c.getDouble(1), c.getDouble(2),
                        c.getDouble(3), c.getDouble(4), c.getDouble(5)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * 供 localDbStatus 的 legacy 字段：返回各 (code,period) 旧口径残留行数
     * {"AL|5m":N, ...}，仅覆盖 5m/15m/30m/60m（1m/1d 不产生条目）。
     * 结果缓存，避免每次全表扫；任何 K 线写库都会清缓存。
     */
    public JSONObject countLegacyBars() {
        JSONObject cached = legacyCache;
        if (cached != null) return cached;
        synchronized (legacyLock) {
            cached = legacyCache;
            if (cached != null) return cached;
            JSONObject out = new JSONObject();
            SQLiteDatabase db = getWritableDatabase();
            try {
                SeedIndex idx = loadSeedIndex(appCtx);
                if (idx != null) {
                    buildSeedTempTables(db, idx);
                    Cursor c = db.rawQuery(
                            "SELECT code,period,COUNT(*) FROM kline WHERE " + LEGACY_WHERE
                                    + " GROUP BY code,period", null);
                    try {
                        while (c.moveToNext()) {
                            out.put(c.getString(0) + "|" + c.getString(1), c.getInt(2));
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                dropSeedTempTables(db);
            }
            legacyCache = out;
            return out;
        }
    }

    /**
     * 旧口径自动迁移：把 assets/seed.db 拷到 cacheDir 只读打开，读每个 (code,period)
     * 的 ts 集合（只处理 5m/15m/30m/60m），删除本地 kline 中「ts 落在 seed 该组合
     * [min,max] 区间内且不在 seed ts 集合」的行，返回删除总数；异常返回 -1 不崩。
     * 1m/1d 不动（1m 本地可能有比 seed 更新的数据）。
     */
    public int migrateLegacyBars(Context ctx) {
        synchronized (legacyLock) {
            SQLiteDatabase db = getWritableDatabase();
            try {
                SeedIndex idx = loadSeedIndex(ctx);
                if (idx == null) return -1;
                buildSeedTempTables(db, idx);
                int deleted;
                Cursor c = db.rawQuery(
                        "SELECT COUNT(*) FROM kline WHERE " + LEGACY_WHERE, null);
                try {
                    deleted = c.moveToFirst() ? c.getInt(0) : 0;
                } finally {
                    c.close();
                }
                if (deleted > 0) db.execSQL("DELETE FROM kline WHERE " + LEGACY_WHERE);
                invalidateLegacyCache();
                return deleted;
            } catch (Exception e) {
                return -1;
            } finally {
                dropSeedTempTables(db);
            }
        }
    }

    /** 解析 assets/seed.db 构建进程级 seed 索引；失败返回 null（调用方判失败，不崩）。 */
    private SeedIndex loadSeedIndex(Context ctx) {
        SeedIndex cached = seedIndex;
        if (cached != null) return cached;
        synchronized (Store.class) {
            cached = seedIndex;
            if (cached != null) return cached;
            File tmp = new File(ctx.getCacheDir(), "seed_legacy.db");
            SQLiteDatabase seed = null;
            try {
                if (tmp.exists() && !tmp.delete()) { /* 忽略旧副本删除失败 */ }
                try (InputStream in = ctx.getAssets().open("seed.db");
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                seed = SQLiteDatabase.openDatabase(
                        tmp.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                SeedIndex idx = new SeedIndex();
                Cursor c = seed.rawQuery(
                        "SELECT code,period,ts FROM kline WHERE period IN (" + LEGACY_IN + ")",
                        null);
                try {
                    while (c.moveToNext()) {
                        String key = c.getString(0) + "|" + c.getString(1);
                        long ts = c.getLong(2);
                        Set<Long> set = idx.ts.get(key);
                        if (set == null) {
                            set = new HashSet<>();
                            idx.ts.put(key, set);
                        }
                        set.add(ts);
                        long[] b = idx.bounds.get(key);
                        if (b == null) {
                            idx.bounds.put(key, new long[]{ts, ts});
                        } else {
                            if (ts < b[0]) b[0] = ts;
                            if (ts > b[1]) b[1] = ts;
                        }
                    }
                } finally {
                    c.close();
                }
                seedIndex = idx;
                return idx;
            } catch (Exception e) {
                return null;
            } finally {
                if (seed != null) {
                    try {
                        seed.close();
                    } catch (Exception ignored) {
                    }
                }
                if (tmp.exists()) tmp.delete();
            }
        }
    }

    /** 在当前连接重建 seed_ts / seed_bounds 两张临时表并灌入 seed 索引。 */
    private void buildSeedTempTables(SQLiteDatabase db, SeedIndex idx) {
        db.execSQL("DROP TABLE IF EXISTS seed_ts");
        db.execSQL("DROP TABLE IF EXISTS seed_bounds");
        db.execSQL("CREATE TEMP TABLE seed_ts(k TEXT PRIMARY KEY, code TEXT, period TEXT, ts INTEGER)");
        db.execSQL("CREATE TEMP TABLE seed_bounds(code TEXT, period TEXT, lo INTEGER, hi INTEGER,"
                + " PRIMARY KEY(code,period))");
        SQLiteStatement ins = null, insB = null;
        db.beginTransaction();
        try {
            ins = db.compileStatement(
                    "INSERT OR IGNORE INTO seed_ts(k,code,period,ts) VALUES(?,?,?,?)");
            for (Map.Entry<String, Set<Long>> e : idx.ts.entrySet()) {
                String key = e.getKey();
                int bar = key.indexOf('|');
                String code = bar >= 0 ? key.substring(0, bar) : key;
                String period = bar >= 0 ? key.substring(bar + 1) : "";
                for (long t : e.getValue()) {
                    ins.clearBindings();
                    ins.bindString(1, key + "|" + t);
                    ins.bindString(2, code);
                    ins.bindString(3, period);
                    ins.bindLong(4, t);
                    ins.executeInsert();
                }
            }
            insB = db.compileStatement(
                    "INSERT OR REPLACE INTO seed_bounds(code,period,lo,hi) VALUES(?,?,?,?)");
            for (Map.Entry<String, long[]> e : idx.bounds.entrySet()) {
                int bar = e.getKey().indexOf('|');
                insB.clearBindings();
                insB.bindString(1, bar >= 0 ? e.getKey().substring(0, bar) : e.getKey());
                insB.bindString(2, bar >= 0 ? e.getKey().substring(bar + 1) : "");
                insB.bindLong(3, e.getValue()[0]);
                insB.bindLong(4, e.getValue()[1]);
                insB.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            try {
                if (ins != null) ins.close();
            } catch (Exception ignored) {
            }
            try {
                if (insB != null) insB.close();
            } catch (Exception ignored) {
            }
            db.endTransaction();
        }
    }

    private void dropSeedTempTables(SQLiteDatabase db) {
        try {
            db.execSQL("DROP TABLE IF EXISTS seed_ts");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("DROP TABLE IF EXISTS seed_bounds");
        } catch (Exception ignored) {
        }
    }

    /** 任何 K 线写库后清掉 legacy 结果缓存。 */
    private void invalidateLegacyCache() {
        legacyCache = null;
    }
}
