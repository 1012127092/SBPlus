package com.sbplus.browser;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CookieHelper — 直接读写三星浏览器引擎的 Chromium cookie 数据库
 * (/data/data/com.sec.android.app.sbrowser/app_sbrowser/Default/Cookies)。
 *
 * 实测该库 value 为明文, 表结构为标准 Chromium cookies 表:
 *   host_key, name, value, path, expires_utc, is_secure, is_httponly, is_persistent, ...
 *
 * 本模块运行在浏览器进程内, 因此可直接 open 该 SQLite(读写)。
 */
public final class CookieHelper {

    private static final String COOKIE_DB = "/data/user/0/com.sec.android.app.sbrowser/app_sbrowser/Default/Cookies";

    private CookieHelper() {}

    private static String dbPath(Context ctx) {
        // 优先用 context.dataDir 构造(与探测一致), 失败用硬编码
        try {
            File f = new File(ctx.getApplicationInfo().dataDir, "app_sbrowser/Default/Cookies");
            if (f.exists()) return f.getAbsolutePath();
        } catch (Throwable ignored) {}
        return COOKIE_DB;
    }

    /** 枚举所有有 cookie 的 host(去点前缀, 排序)。返回 host 列表。 */
    public static List<String> listHosts(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            SQLiteDatabase db = openRO(ctx);
            if (db == null) return out;
            Cursor c = db.rawQuery("SELECT DISTINCT host_key FROM cookies ORDER BY host_key", null);
            while (c.moveToNext()) {
                String h = c.getString(0);
                if (h == null || h.isEmpty()) continue;
                if (h.startsWith(".")) h = h.substring(1);
                out.add(h);
            }
            c.close(); db.close();
        } catch (Throwable t) { XposedBridgeLog("listHosts err: " + t); }
        return out;
    }

    /** 读取某 host 的所有 cookie 键值(保序)。返回 [name,value][]. */
    public static List<String[]> readHostCookies(Context ctx, String rawHost) {
        List<String[]> out = new ArrayList<>();
        try {
            SQLiteDatabase db = openRO(ctx);
            if (db == null) return out;
            Cursor c = db.rawQuery("SELECT name, value, path, is_secure, is_httponly FROM cookies WHERE host_key=? ORDER BY name",
                    new String[]{ rawHost });
            while (c.moveToNext()) {
                out.add(new String[]{ c.getString(0), c.getString(1), c.getString(2),
                        String.valueOf(c.getInt(3)), String.valueOf(c.getInt(4)) });
            }
            c.close(); db.close();
        } catch (Throwable t) { XposedBridgeLog("readHost err: " + t); }
        return out;
    }

    /** 读某 host 原始 cookie 串(k=v; k2=v2)。无则空串。 */
    public static String readCookies(Context ctx, String host) {
        List<String[]> kvs = readHostCookies(ctx, host);
        StringBuilder sb = new StringBuilder();
        for (String[] kv : kvs) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(kv[0]).append("=").append(kv[1]);
        }
        return sb.toString();
    }

    /** 更新/插入单个 cookie(按 host+name+path 覆盖)。返回是否成功。 */
    public static boolean setCookie(Context ctx, String rawHost, String name, String value, String path, boolean secure, boolean httpOnly) {
        try {
            SQLiteDatabase db = openRW(ctx);
            if (db == null) return false;
            if (path == null || path.isEmpty()) path = "/";
            int sec = secure ? 1 : 0;
            int ho = httpOnly ? 1 : 0;
            long now = System.currentTimeMillis() * 1000L;
            db.execSQL("DELETE FROM cookies WHERE host_key=? AND name=? AND path=?",
                    new Object[]{ rawHost, name, path });
            db.execSQL("INSERT INTO cookies(creation_utc,host_key,name,value,path,expires_utc,is_secure,is_httponly,last_access_utc,has_expires,is_persistent,priority,source_scheme,last_update_utc,has_cross_site_ancestor) " +
                            "VALUES(?,?,?,?,?,0,?,?,?,0,0,1,2,?,0)",
                    new Object[]{ now, rawHost, name, value, path, sec, ho, now, now });
            db.close();
            return true;
        } catch (Throwable t) { XposedBridgeLog("set err: " + t); return false; }
    }

    /** 删除某 host 的指定 cookie。 */
    public static boolean deleteCookie(Context ctx, String rawHost, String name) {
        try {
            SQLiteDatabase db = openRW(ctx);
            if (db == null) return false;
            db.execSQL("DELETE FROM cookies WHERE host_key=? AND name=?", new Object[]{ rawHost, name });
            db.close();
            return true;
        } catch (Throwable t) { XposedBridgeLog("del err: " + t); return false; }
    }

    /** 删除某 host 全部 cookie。返回删除条数。 */
    public static int clearHost(Context ctx, String rawHost) {
        try {
            SQLiteDatabase db = openRW(ctx);
            if (db == null) return 0;
            int n = db.delete("cookies", "host_key=?", new String[]{ rawHost });
            db.close();
            return n;
        } catch (Throwable t) { XposedBridgeLog("clear err: " + t); return 0; }
    }

    /** 批量写入(覆盖同名)。返回成功数。 */
    public static int setCookies(Context ctx, String rawHost, List<String[]> kvs) {
        int ok = 0;
        if (kvs == null) return 0;
        for (String[] kv : kvs) {
            if (kv != null && kv.length >= 2 && !kv[0].isEmpty()) {
                String path = kv.length > 2 && kv[2] != null ? kv[2] : "/";
                if (setCookie(ctx, rawHost, kv[0], kv[1], path, false, false)) ok++;
            }
        }
        return ok;
    }

    private static SQLiteDatabase openRO(Context ctx) {
        try {
            return SQLiteDatabase.openDatabase(dbPath(ctx), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) { XposedBridgeLog("openRO err: " + t); return null; }
    }
    private static SQLiteDatabase openRW(Context ctx) {
        try {
            return SQLiteDatabase.openDatabase(dbPath(ctx), null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Throwable t) { XposedBridgeLog("openRW err: " + t); return null; }
    }

    private static void XposedBridgeLog(String m) {
        try { de.robv.android.xposed.XposedBridge.log("[SBPlus] CookieHelper " + m); }
        catch (Throwable ignored) {}
    }
}
