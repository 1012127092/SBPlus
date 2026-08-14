package com.sbplus.browser;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

/**
 * Logging facade used by the Xposed hook (running inside the Samsung Browser process).
 *
 * Each log line is reported to the SBPlus app's LogProvider (authority
 * com.sbplus.browser.log) via ContentResolver.insert, so it ends up stored in the module
 * app's own filesDir — where the app can then display / export / delete it and where the
 * retention cleanup lives. This sidesteps the SELinux restriction that blocks direct
 * cross-UID file writes from the browser process.
 *
 * Before the target app's Application context is captured, we only mirror to
 * XposedBridge.log (logcat).
 */
public final class LogWriter {

    private static final Uri LOG_URI = Uri.parse("content://com.sbplus.browser.log");

    private static volatile Context sContext;

    private LogWriter() {}

    /** Called once the target app Application context is captured. */
    public static void init(Context ctx) {
        if (ctx == null) return;
        sContext = ctx;
        de.robv.android.xposed.XposedBridge.log("[SBPlus] LogWriter ready (provider-backed)");
    }

    /** Log a message to logcat and forward it to the module app's log store. */
    public static void log(String tag, String msg) {
        String line = "[" + tag + "] " + msg;
        de.robv.android.xposed.XposedBridge.log("[SBPlus] " + line);

        Context ctx = sContext;
        if (ctx == null) return; // context not ready yet → logcat only

        try {
            ContentValues v = new ContentValues();
            v.put("tag", tag);
            v.put("msg", msg);
            ctx.getContentResolver().insert(LOG_URI, v);
        } catch (Throwable ignored) {
            // Best-effort logging; never let logging break the hook.
        }
    }
}
