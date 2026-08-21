package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 主页时钟管理: 支持精确到秒, 自定义大小位置.
 */
public class HomeClockHelper {

    public static final String PREFS = "sbplus_home_clock";

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    public static void setEnabled(Context ctx, boolean en) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", en).apply();
    }

    /** 精确到秒. */
    public static boolean isSeconds(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("seconds", true);
    }

    public static void setSeconds(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("seconds", on).apply();
    }

    /** 位置: X 百分比(0-100, 锚点中心). */
    public static int getPosX(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("posx", 50);
    }

    public static void setPosX(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("posx", v).apply();
    }

    /** 位置: Y 百分比(0-100, 锚点中心). */
    public static int getPosY(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("posy", 30);
    }

    public static void setPosY(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("posy", v).apply();
    }

    /** 大小: 百分比(50-200, 100=默认). */
    public static int getSizePct(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("size", 100);
    }

    public static void setSizePct(Context ctx, int v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("size", v).apply();
    }

    /** 跟随搜索框动画. */
    public static boolean isFollow(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("follow", true);
    }

    public static void setFollow(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("follow", on).apply();
    }
}