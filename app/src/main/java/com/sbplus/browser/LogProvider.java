package com.sbplus.browser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * ContentProvider that stores the SBPlus module logs inside this app's own filesDir.
 *
 * The Xposed hook runs inside the Samsung Browser process (a different UID), so it cannot
 * write directly into this app's private storage. Instead it reports each log line via
 * ContentResolver.insert() on this provider, and we append it to the daily log file here
 * (same process/uid as the file, so no SELinux issue).
 *
 * The provider also owns retention cleanup (age + size), so the UI (LogManagerActivity)
 * can read/export/delete logs and edit retention settings all from within this app, with
 * no cross-uid file access needed.
 *
 * Supported operations (authority com.sbplus.browser.log):
 *   insert  -> append a log line (values: "tag", "msg")
 *   query   -> read current logs (path "list" returns file names; path "content" returns text)
 *   delete  -> delete all log files
 *   call    -> "cleanup" triggers explicit retention cleanup; "get_path" returns the dir path
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.sbplus.browser.log";

    private static final String LOG_DIR = "sbplus_logs";
    private static final String CONFIG_PREFS = "sbplus_log_config";
    private static final String KEY_KEEP_DAYS = "log_keep_days";
    private static final String KEY_MAX_MB = "log_max_mb";
    public static final int DEFAULT_KEEP_DAYS = 7;
    public static final int DEFAULT_MAX_MB = 10;

    private static final SimpleDateFormat DAY_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    @Override
    public boolean onCreate() {
        return true;
    }

    private File logDir() {
        File dir = new File(getContext().getFilesDir(), LOG_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private int keepDays() {
        return getConfig().getInt(KEY_KEEP_DAYS, DEFAULT_KEEP_DAYS);
    }

    private long maxBytes() {
        int mb = getConfig().getInt(KEY_MAX_MB, DEFAULT_MAX_MB);
        return (long) mb * 1024L * 1024L;
    }

    private SharedPreferences getConfig() {
        return getContext().getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE);
    }

    /** Append a log line to the daily file, then occasionally run retention cleanup. */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values == null) return uri;
        String tag = values.getAsString("tag");
        String msg = values.getAsString("msg");
        String line = "[" + (tag == null ? "?" : tag) + "] " + (msg == null ? "" : msg);

        try {
            String day = DAY_FMT.format(new Date());
            File f = new File(logDir(), "sbplus_" + day + ".log");
            FileWriter fw = new FileWriter(f, true);
            fw.write(TS_FMT.format(new Date()) + " " + line + "\n");
            fw.close();
            maybeCleanup();
        } catch (IOException ignored) {
        }
        return uri;
    }

    /** Query logs. path "content" -> full text of all logs; any other -> file-name list. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String path = uri.getLastPathSegment();
        if ("content".equals(path)) {
            MatrixCursor c = new MatrixCursor(new String[]{"text"});
            c.addRow(new Object[]{readAllLogs()});
            return c;
        }
        File[] files = logDir().listFiles();
        MatrixCursor c = new MatrixCursor(new String[]{"name"});
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (File f : files) c.addRow(new Object[]{f.getName()});
        }
        return c;
    }

    /** Delete all log files. */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int n = 0;
        File[] files = logDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.delete()) n++;
            }
        }
        return n;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if ("cleanup".equals(method)) {
            cleanup(logDir());
            out.putBoolean("ok", true);
        } else if ("get_path".equals(method)) {
            out.putString("path", logDir().getAbsolutePath());
        }
        return out;
    }

    @Override
    public String getType(Uri uri) { return "vnd.android.cursor.item/sbplus.log"; }

    @Override
    public int update(Uri uri, ContentValues values, String s, String[] sa) { return 0; }

    private String readAllLogs() {
        File[] files = logDir().listFiles();
        StringBuilder sb = new StringBuilder();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : files) {
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    byte[] buf = new byte[(int) f.length()];
                    int n = fis.read(buf);
                    fis.close();
                    if (n > 0) {
                        sb.append("===== ").append(f.getName()).append(" =====\n");
                        sb.append(new String(buf, 0, n));
                        sb.append("\n");
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return sb.toString();
    }

    /** Trigger retention cleanup on roughly every insert (cheap; small file count). */
    private void maybeCleanup() {
        // Throttle: run at most once per second regardless of insert rate.
        long now = System.currentTimeMillis();
        if (now - sLastCleanup < 1000L) return;
        sLastCleanup = now;
        cleanup(logDir());
    }

    private static long sLastCleanup = 0;

    /** Enforce age + size retention on the log directory. */
    private void cleanup(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        long now = System.currentTimeMillis();
        long ageCutoff = now - keepDays() * 24L * 3600L * 1000L;
        long maxBytes = maxBytes();

        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });

        long total = 0;
        for (File f : files) total += f.length();

        for (File f : files) {
            boolean agedOut = f.lastModified() < ageCutoff;
            boolean overBudget = total > maxBytes;
            if (!agedOut && !overBudget) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }
}
