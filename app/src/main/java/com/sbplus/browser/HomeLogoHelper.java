package com.sbplus.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 主页 Logo 管理: 选本地图片/GIF 复制到应用私有目录, 支持多张切换.
 * 存储: getExternalFilesDir(null)/home_logos/ 下的文件, SharedPreferences 记录当前选中的文件名.
 */
public class HomeLogoHelper {

    public static final String PREFS = "sbplus_home_logo";
    public static final String KEY_CURRENT = "current";
    public static final String KEY_LIST = "list";
    public static final String KEY_ENABLED = "enabled";

    private static File dir(Context ctx) {
        return dirFor(ctx);
    }

    /** 公开: Logo 存储目录。 */
    public static File dirFor(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "home_logos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 把选中的图片复制到私有目录, 返回文件名(带扩展名), 失败返回 null. */
    public static String addLogoFromUri(Context ctx, Uri uri) {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            String name = "logo_" + System.currentTimeMillis();
            String mime = ctx.getContentResolver().getType(uri);
            if (mime != null && mime.contains("gif")) name += ".gif";
            else if (mime != null && mime.contains("png")) name += ".png";
            else if (mime != null && mime.contains("webp")) name += ".webp";
            else name += ".jpg";
            File out = new File(dir(ctx), name);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            // 加入列表
            List<String> list = listLogos(ctx);
            if (!list.contains(name)) {
                list.add(name);
                saveList(ctx, list);
            }
            // 自动设为当前
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_CURRENT, name).putBoolean(KEY_ENABLED, true).apply();
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void saveList(Context ctx, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, sb.toString()).apply();
    }

    public static List<String> listLogos(Context ctx) {
        List<String> res = new ArrayList<String>();
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, "");
            if (raw != null && !raw.isEmpty()) {
                for (String s : raw.split(",")) {
                    if (!s.isEmpty()) res.add(s);
                }
            }
            // 兜底: 目录里实际存在的文件
            File[] fs = dir(ctx).listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (!res.contains(f.getName())) res.add(f.getName());
                }
            }
        } catch (Throwable ignored) {}
        return res;
    }

    /** 当前选中 Logo 的绝对路径, 无则空串. */
    public static String currentPath(Context ctx) {
        try {
            String name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT, "");
            if (name == null || name.isEmpty()) return "";
            File f = new File(dir(ctx), name);
            return f.exists() ? f.getAbsolutePath() : "";
        } catch (Throwable t) { return ""; }
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean en) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, en).apply();
    }

    public static void setCurrent(Context ctx, String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT, name).apply();
    }

    public static void removeLogo(Context ctx, String name) {
        try {
            File f = new File(dir(ctx), name);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
        List<String> list = listLogos(ctx);
        list.remove(name);
        saveList(ctx, list);
        String cur = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT, "");
        if (name.equals(cur)) {
            String next = list.isEmpty() ? "" : list.get(0);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT, next).apply();
        }
    }

    /** 当前 logo 是否启用背景透明化。 */
    public static boolean isAlphaBg(Context ctx, String name) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("alpha_" + name, false);
        } catch (Throwable ignored) {}
        return false;
    }

    /** 设置当前 logo 背景透明化开关。 */
    public static void setAlphaBg(Context ctx, String name, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("alpha_" + name, on).apply();
        } catch (Throwable ignored) {}
    }

    /** 位置: X 百分比(0-100, 水平锚点在 Logo 中心)。 */
    public static int getPosX(Context ctx, String name) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("posx_" + name, 50);
        } catch (Throwable ignored) {}
        return 50;
    }

    public static void setPosX(Context ctx, String name, int v) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("posx_" + name, v).apply();
        } catch (Throwable ignored) {}
    }

    /** 位置: Y 百分比(0-100, 垂直锚点在 Logo 中心)。 */
    public static int getPosY(Context ctx, String name) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("posy_" + name, 20);
        } catch (Throwable ignored) {}
        return 20;
    }

    public static void setPosY(Context ctx, String name, int v) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("posy_" + name, v).apply();
        } catch (Throwable ignored) {}
    }

    /** 大小: 百分比(50-200, 100=默认)。 */
    public static int getSizePct(Context ctx, String name) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("size_" + name, 100);
        } catch (Throwable ignored) {}
        return 100;
    }

    public static void setSizePct(Context ctx, String name, int v) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("size_" + name, v).apply();
        } catch (Throwable ignored) {}
    }

    /** 跟随搜索框动画(全局). */
    public static boolean isFollow(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("follow", true);
        } catch (Throwable ignored) {}
        return true;
    }

    public static void setFollow(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("follow", on).apply();
        } catch (Throwable ignored) {}
    }
}