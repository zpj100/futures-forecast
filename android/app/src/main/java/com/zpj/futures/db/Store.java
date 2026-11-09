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
