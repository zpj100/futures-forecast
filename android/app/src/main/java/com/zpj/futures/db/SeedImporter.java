package com.zpj.futures.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.zpj.futures.Cfg;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;

/**
 * 把 APK 内置的 assets/seed.db（预置分钟线 / 日线）导入手机本地库。
 *
 * <p>触发条件（任一满足即导入）：
 * <ul>
 *   <li>预置库版本比上次导入的新；</li>
 *   <li>本地 kline 表是空的（自愈：v2.1.1 及更早因版本号 int 溢出漏导过，装新版要能补上）。</li>
 * </ul>
 *
 * <p>v2.1.2 修复：{@code seed_meta.version} 是 12 位时间戳（如 202611092054），
 * 老代码用 {@code Integer.parseInt} 解析必然抛 NumberFormatException，被 catch 成 0 后
 * 直接判定"无需导入"，因此预置库 1.9 万根 K 线从未真正进过手机库——
 * 这就是"装完没有本地数据、曲线一直等待数据"的根因。现改为 long 解析。
 */
public class SeedImporter {

    private static final String TAG = "SeedImporter";
    private static final String ASSET = "seed.db";
    private static final String VER_ASSET = "seed_ver.txt";

    /** 返回导入后本地 kline 总行数；-1 表示失败；0 表示无需导入（本地已有数据）。 */
    public static int importIfNeeded(Context ctx, Cfg cfg, Store store) {
        long assetVer = readAssetVersion(ctx);
        int localBefore = store.countKlineAll();

        // 小文件先比对版本，避免每次启动都去拷贝 1.5MB 的 seed.db
        if (assetVer > 0 && assetVer <= cfg.seedVersion() && localBefore > 0) {
            return 0;
        }

        long ver = 0;
        int seedRows = 0;
        File tmp = new File(ctx.getCacheDir(), "seed.db");
        try {
            if (tmp.exists() && !tmp.delete()) Log.w(TAG, "无法删除旧的 seed 副本");
            try (InputStream in = ctx.getAssets().open(ASSET);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            SQLiteDatabase seed = SQLiteDatabase.openDatabase(
                    tmp.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try {
                Cursor c = seed.rawQuery(
                        "SELECT value FROM seed_meta WHERE key='version'", null);
                if (c.moveToFirst()) ver = parseLong(c.getString(0));
                c.close();
                Cursor c2 = seed.rawQuery("SELECT COUNT(*) FROM kline", null);
                seedRows = c2.moveToFirst() ? c2.getInt(0) : 0;
                c2.close();
            } finally {
                seed.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "读取预置库失败：" + e.getMessage());
            if (tmp.exists()) tmp.delete();
            return -1;
        }

        if (ver <= 0) ver = assetVer > 0 ? assetVer : 1;   // 版本号异常也要能导入
        if (seedRows <= 0) {
            tmp.delete();
            Log.w(TAG, "预置库没有 K 线数据");
            return -1;
        }
        if (ver <= cfg.seedVersion() && localBefore > 0) {
            tmp.delete();
            return 0;
        }

        int after;
        try {
            int added = importByAttach(store, tmp);
            if (added < 0) added = importByRows(store, tmp);
            if (added < 0) {
                Log.w(TAG, "预置库导入失败（ATTACH 与逐行两条路径都失败）");
                return -1;
            }
            cfg.setSeedVersion(ver);
            if (cfg.seedImportedAt() <= 0) cfg.setSeedImportedAt(System.currentTimeMillis());
            after = store.countKlineAll();
            Log.i(TAG, "预置行情已导入：" + (after - localBefore) + " 行新增 / 共 "
                    + after + " 行，version=" + ver + "，seed=" + seedRows);
        } catch (Exception e) {
            Log.w(TAG, "导入预置库失败：" + e.getMessage());
            return -1;
        } finally {
            tmp.delete();
        }
        return after;
    }

    /** 主路径：ATTACH + 一条 INSERT…SELECT（19k 行约 100–300ms）。失败返回 -1。 */
    private static int importByAttach(Store store, File tmp) {
        SQLiteDatabase db = store.getWritableDatabase();
        int before = store.countKlineAll();
        try {
            String path = tmp.getPath().replace("'", "''");
            db.execSQL("ATTACH DATABASE '" + path + "' AS seed");
            try {
                db.execSQL("INSERT OR IGNORE INTO kline(code,period,ts,o,h,l,c,v) "
                        + "SELECT code,period,ts,o,h,l,c,v FROM seed.kline");
                try {
                    db.execSQL("INSERT OR IGNORE INTO ticks(code,ts,price,bid,ask,vol,oi) "
                            + "SELECT code,ts,price,bid,ask,vol,oi FROM seed.ticks");
                } catch (Exception ignored) {
                    // 预置库不含秒级点属正常情况
                }
            } finally {
                try {
                    db.execSQL("DETACH DATABASE seed");
                } catch (Exception ignored) {
                }
            }
            return store.countKlineAll() - before;
        } catch (Exception e) {
            Log.w(TAG, "ATTACH 导入失败，改逐行：" + e.getMessage());
            try {
                db.execSQL("DETACH DATABASE seed");
            } catch (Exception ignored) {
            }
            return -1;
        }
    }

    /** 兜底路径：另开只读句柄读 seed，分批 ContentValues 写入。失败返回 -1。 */
    private static int importByRows(Store store, File tmp) {
        SQLiteDatabase seed = null;
        try {
            seed = SQLiteDatabase.openDatabase(tmp.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            SQLiteDatabase db = store.getWritableDatabase();
            int before = store.countKlineAll();
            int total = 0;
            Cursor c = seed.rawQuery(
                    // 只读句柄直接打开 seed.db，未 ATTACH，故用默认库 kline（非 seed.kline）
                    "SELECT code,period,ts,o,h,l,c,v FROM kline", null);
            List<ContentValues> batch = new ArrayList<>(800);
            db.beginTransaction();
            try {
                while (c.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    cv.put("code", c.getString(0));
                    cv.put("period", c.getString(1));
                    cv.put("ts", c.getLong(2));
                    cv.put("o", c.getDouble(3));
                    cv.put("h", c.getDouble(4));
                    cv.put("l", c.getDouble(5));
                    cv.put("c", c.getDouble(6));
                    cv.put("v", c.getDouble(7));
                    batch.add(cv);
                    if (batch.size() >= 800) {
                        total += flush(db, batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) total += flush(db, batch);
                db.setTransactionSuccessful();
            } finally {
                c.close();
                db.endTransaction();
            }
            return store.countKlineAll() - before;
        } catch (Exception e) {
            Log.w(TAG, "逐行导入失败：" + e.getMessage());
            return -1;
        } finally {
            if (seed != null) seed.close();
        }
    }

    private static int flush(SQLiteDatabase db, List<ContentValues> batch) {
        int n = 0;
        for (ContentValues cv : batch) {
            long id = db.insertWithOnConflict("kline", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            if (id >= 0) n++;
        }
        return n;
    }

    /** 读 assets/seed_ver.txt（构建脚本写入的小文件），读不到返回 0。 */
    private static long readAssetVersion(Context ctx) {
        try (InputStream in = ctx.getAssets().open(VER_ASSET)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return parseLong(bo.toString("UTF-8"));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 12 位时间戳版本号必须按 long 解析，按 int 会溢出。 */
    private static long parseLong(String s) {
        if (s == null) return 0;
        try {
            String v = s.trim();
            if (v.isEmpty()) return 0;
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }
}
