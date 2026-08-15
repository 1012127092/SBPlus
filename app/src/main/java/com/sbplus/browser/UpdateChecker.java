package com.sbplus.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub release update checker for SBPlus.
 *
 * Queries the latest release info from the GitHub REST API and compares the
 * remote tag against the locally-installed version. Download is delegated to the
 * system (browser / DownloadManager) once the user confirms.
 */
public class UpdateChecker {

    public interface Callback {
        void onResult(UpdateInfo info, String error);
    }

    /** Parsed result of a successful latest-release lookup. */
    public static class UpdateInfo {
        public final String tagName;      // e.g. "v1.1" or "1.1"
        public final String name;         // release title
        public final String body;         // release notes
        public final String downloadUrl;  // apk browser_download_url
        public final boolean newer;       // remote vs local

        UpdateInfo(String tagName, String name, String body, String downloadUrl, boolean newer) {
            this.tagName = tagName;
            this.name = name;
            this.body = body;
            this.downloadUrl = downloadUrl;
            this.newer = newer;
        }
    }

    private static final String TAG = "SBPlusUpdate";
    private static final String REPO = "1012127092/SBPlus";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String REPO_PAGE = "https://github.com/" + REPO;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Project homepage (human-readable). */
    public static String projectUrl() {
        return REPO_PAGE;
    }

    /**
     * Fetch latest release info asynchronously and report via callback on the main thread.
     * Never throws; errors are delivered through callback.error.
     */
    public static void check(String localVersion, final Callback cb) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                UpdateInfo info = null;
                String error = null;
                try {
                    info = query(localVersion);
                } catch (Exception e) {
                    error = e.getMessage();
                    Log.w(TAG, "check failed", e);
                }
                final UpdateInfo fInfo = info;
                final String fErr = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fInfo, fErr);
                    }
                });
            }
        });
    }

    private static UpdateInfo query(String localVersion) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(LATEST_URL).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "SBPlus");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        c.disconnect();

        JSONObject o = new JSONObject(sb.toString());
        String tag = o.optString("tag_name", "");
        String name = o.optString("name", "");
        if (name == null || name.isEmpty()) name = tag;
        String body = o.optString("body", "");

        String downloadUrl = null;
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String url = a.optString("browser_download_url", null);
                if (url != null && url.endsWith(".apk")) {
                    downloadUrl = url;
                    break;
                }
            }
        }

        boolean newer = isNewer(tag, localVersion);
        return new UpdateInfo(tag, name, body, downloadUrl, newer);
    }

    /**
     * Compare two version strings (numeric dotted or "v"-prefixed). Returns true when
     * remote > local. Falls back to plain string inequality if not parseable.
     */
    static boolean isNewer(String remote, String local) {
        String r = (remote == null ? "" : remote).trim();
        String l = (local == null ? "" : local).trim();
        if (r.isEmpty()) return false;
        if (l.isEmpty()) return true;
        // strip leading 'v'
        if (r.toLowerCase().startsWith("v")) r = r.substring(1);
        if (l.toLowerCase().startsWith("v")) l = l.substring(1);
        try {
            String[] rp = r.split("\\.");
            String[] lp = l.split("\\.");
            int n = Math.max(rp.length, lp.length);
            for (int i = 0; i < n; i++) {
                int rv = i < rp.length ? Integer.parseInt(rp[i].trim()) : 0;
                int lv = i < lp.length ? Integer.parseInt(lp[i].trim()) : 0;
                if (rv != lv) return rv > lv;
            }
            return false;
        } catch (NumberFormatException e) {
            return !r.equals(l);
        }
    }

    /** Open the apk download URL in the user's browser. */
    public static void openDownload(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "openDownload failed", e);
        }
    }
}
