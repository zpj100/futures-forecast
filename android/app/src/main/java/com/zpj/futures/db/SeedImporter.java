package com.zpj.futures.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.zpj.futures.Cfg;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 把 APK 内置的 assets/seed.db（预置分钟线 / 日线）导入手机本地库。
 *
 * 只在 seed 版本变新时执行一次；导入用 INSERT OR IGNORE，
 * 手机上已有的（可能更新的）同 key 记录不会被覆盖。
 */
public class SeedImporter {

    private static final String TAG = "SeedImporter";
    private static final String ASSET = "seed.db";

    /** 返回导入的 K 线行数；0 表示无需导入。必须在后台或启动早期调用。 */
    public static int importIfNeeded(Context ctx, Cfg cfg, Store store) {
        int assetVer = 0;
        File tmp = new File(ctx.getCacheDir(), "seed.db");
        try {
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "无法删除旧的 seed 副本");
            }
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
                assetVer = c.moveToFirst() ? parseInt(c.getString(0)) : 0;
                c.close();
            } finally {
                seed.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "读取预置库失败：" + e.getMessage());
            return 0;
        }

        if (assetVer <= 0 || assetVer <= cfg.seedVersion()) {
            tmp.delete();
            return 0;
        }

        int rows = 0;
        SQLiteDatabase db = store.getWritableDatabase();
        try {
            String path = tmp.getPath().replace("'", "''");
            db.execSQL("ATTACH DATABASE '" + path + "' AS seed");
            try {
                db.execSQL("INSERT OR IGNORE INTO kline(code,period,ts,o,h,l,c,v) "
                        + "SELECT code,period,ts,o,h,l,c,v FROM seed.kline");
                Cursor c = db.rawQuery(
                        "SELECT COUNT(*) FROM seed.kline", null);
                rows = c.moveToFirst() ? c.getInt(0) : 0;
                c.close();
            } finally {
                db.execSQL("DETACH DATABASE seed");
            }
            cfg.setSeedVersion(assetVer);
            Log.i(TAG, "预置行情已导入：" + rows + " 行，version=" + assetVer);
        } catch (Exception e) {
            Log.w(TAG, "导入预置库失败：" + e.getMessage());
        } finally {
            tmp.delete();
        }
        return rows;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
