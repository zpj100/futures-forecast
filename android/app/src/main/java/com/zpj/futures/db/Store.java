package com.zpj.futures.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机端本地库：秒级快照 / K 线 / 预测留档。
 * 全部为「手机内存储」，不依赖任何后端。
 */
public class Store extends SQLiteOpenHelper {

    private static final String NAME = "futures.db";
    private static final int VERSION = 1;
    private static Store inst;

    public static synchronized Store get(Context ctx) {
        if (inst == null) inst = new Store(ctx.getApplicationContext());
        return inst;
    }

    private Store(Context ctx) {
        super(ctx, NAME, null, VERSION);
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
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, price FROM ticks WHERE code=? ORDER BY ts DESC LIMIT ?",
                new String[]{code, String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(0, new double[]{c.getLong(0), c.getDouble(1)});
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
        List<double[]> out = new ArrayList<>();
        int max = Math.max(2, limit);
        long now = System.currentTimeMillis();
        long window = bucketSec > 0
                ? Math.max(bucketSec, 1) * 1000L * max * 2L
                : 86400_000L;
        // 原始点可能很多（30 天 × 每次采集 1 点），先按时间窗截断再取尾部
        String sql = bucketSec > 0
                ? "SELECT ts, price FROM ticks WHERE code=? AND ts>=? ORDER BY ts ASC"
                : "SELECT ts, price FROM (SELECT ts, price FROM ticks WHERE code=? AND ts>=?"
                + " ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC";
        Cursor c = bucketSec > 0
                ? getReadableDatabase().rawQuery(sql, new String[]{code, String.valueOf(now - window)})
                : getReadableDatabase().rawQuery(sql,
                new String[]{code, String.valueOf(now - window), String.valueOf(max)});
        try {
            double curO = 0, curH = 0, curL = 0, curC = 0;
            long curTs = 0;
            int cnt = 0;
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                double p = c.getDouble(1);
                long b = bucketSec > 0 ? (ts / (bucketSec * 1000L)) * (bucketSec * 1000L) : ts;
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
    }

    public List<Bar> klines(String code, String period, int limit) {
        List<Bar> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts,o,h,l,c,v FROM kline WHERE code=? AND period=?"
                        + " ORDER BY ts DESC LIMIT ?",
                new String[]{code, period, String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(0, new Bar(c.getLong(0), c.getDouble(1), c.getDouble(2),
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
        return getWritableDatabase().delete("kline", "period=?",
                new String[]{period});
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
}
