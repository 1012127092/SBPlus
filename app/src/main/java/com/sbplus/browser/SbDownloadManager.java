package com.sbplus.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载任务管理器 + 系统通知栏进度反馈。
 * 运行在浏览器(宿主)进程内。任务状态通过静态 Map 维护，供下载列表页读取。
 */
public class SbDownloadManager {

    public static final String CHANNEL_DOWNLOAD = "sbplus_downloads";

    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_CONVERTING = 2;
    public static final int STATUS_DONE = 3;
    public static final int STATUS_FAILED = 4;

    public static class Task {
        public final String id;
        public String name;          // 文件名
        public int status;           // STATUS_*
        public long totalBytes = 0;  // 已下载字节
        public long partCount = 0;   // 已完成分片
        public long partTotal = 0;   // 总分片
        public long speedBps = 0;    // 当前速度
        public String detail = "";   // 附加信息
        public long lastBytes = 0;
        public long lastTime = 0;
        public String outPath = "";  // 完成后的文件路径

        public Task(String id, String name) {
            this.id = id;
            this.name = name;
            this.lastTime = System.currentTimeMillis();
        }

        public int percent() {
            if (partTotal > 0) return (int) Math.min(100, partCount * 100 / partTotal);
            return 0;
        }
    }

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<String, Task>();
    private static final List<String> ORDER = new ArrayList<String>();

    /** 全局任务并发信号量(控制同时下载的任务数). 容量由设置动态调整. */
    private static java.util.concurrent.Semaphore taskSem = null;
    private static int taskSemCap = -1;

    /** 获取任务并发槽, 若并发已满则阻塞等待. 返回 true 表示获得槽位. */
    public static synchronized boolean acquireTaskSlot(int capacity) {
        try {
            if (capacity < 1) capacity = 1;
            if (taskSem == null || taskSemCap != capacity) {
                taskSem = new java.util.concurrent.Semaphore(capacity);
                taskSemCap = capacity;
            }
            taskSem.acquire();
            return true;
        } catch (Throwable t) { return true; }
    }

    public static void releaseTaskSlot() {
        try { if (taskSem != null) taskSem.release(); } catch (Throwable ignored) {}
    }


    public static synchronized Task register(String id, String name) {
        Task t = new Task(id, name);
        TASKS.put(id, t);
        ORDER.add(id);
        return t;
    }

    public static synchronized Task get(String id) {
        return TASKS.get(id);
    }

    public static synchronized List<Task> all() {
        List<Task> list = new ArrayList<Task>();
        for (String id : ORDER) {
            Task t = TASKS.get(id);
            if (t != null) list.add(t);
        }
        return list;
    }

    public static synchronized Task remove(String id) {
        Task t = TASKS.remove(id);
        if (t != null) ORDER.remove(id);
        return t;
    }

    private static int notifId(String id) {
        return (id.hashCode() & 0x7fffffff) % 50000;
    }

    private static NotificationManager nm(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = nm(ctx);
            if (nm != null && nm.getNotificationChannel(CHANNEL_DOWNLOAD) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_DOWNLOAD,
                        "下载任务", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("SBPlus 下载进度通知");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    public static void post(Context ctx, Task t) {
        try {
            if (ctx == null || t == null) return;
            ensureChannel(ctx);
            NotificationManager nm = nm(ctx);
            if (nm == null) return;

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, CHANNEL_DOWNLOAD);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download);
            b.setContentTitle("下载: " + t.name);
            b.setOngoing(t.status == STATUS_DOWNLOADING || t.status == STATUS_CONVERTING);
            b.setOnlyAlertOnce(true);
            b.setCategory(Notification.CATEGORY_PROGRESS);

            if (t.status == STATUS_DOWNLOADING) {
                b.setContentText(String.format("已完成 %d/%d 分片 · %s",
                        t.partCount, t.partTotal, fmtSpeed(t.speedBps)));
                if (t.partTotal > 0) {
                    b.setProgress(100, t.percent(), false);
                } else {
                    b.setProgress(0, 0, true);
                }
            } else if (t.status == STATUS_CONVERTING) {
                b.setContentText("正在转换 MP4...");
                b.setProgress(0, 0, true);
            } else if (t.status == STATUS_DONE) {
                b.setContentText("下载完成 ✔ · " + t.outPath);
                b.setProgress(0, 0, false);
            } else {
                b.setContentText("下载失败 · " + t.detail);
                b.setProgress(0, 0, false);
            }

            // 通知点击 -> 发广播, 由浏览器进程内的接收器弹出下载列表
            try {
                Intent i = new Intent("com.sbplus.browser.ACTION_SHOW_DOWNLOADS");
                i.setPackage(ctx.getPackageName());
                PendingIntent pi = PendingIntent.getBroadcast(ctx, notifId(t.id), i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.setContentIntent(pi);
            } catch (Throwable ignored) {}

            nm.notify(notifId(t.id), b.build());
        } catch (Throwable ignored) {}
    }

    public static void cancel(Context ctx, String id) {
        try {
            if (ctx == null) return;
            NotificationManager nm = nm(ctx);
            if (nm != null) nm.cancel(notifId(id));
        } catch (Throwable ignored) {}
    }

    public static void clearAll(Context ctx) {
        try {
            if (ctx == null) return;
            NotificationManager nm = nm(ctx);
            if (nm != null) nm.cancelAll();
        } catch (Throwable ignored) {}
    }

    static String fmtSpeed(long bps) {
        try {
            if (bps >= 1048576) return String.format("%.1f MB/s", bps / 1048576.0);
            return (bps / 1024) + " KB/s";
        } catch (Throwable t) { return ""; }
    }
}
