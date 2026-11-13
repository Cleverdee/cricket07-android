package com.cleverdee.cricket07;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/* Updates the app from the project's GitHub releases. The latest release's version is compared with this one; a
   newer one's APK is downloaded and handed to Android's package installer, which replaces the app in place: the
   game files, saves, mods and settings (all in the app's storage) stay. Updates only install over an APK signed
   with the same key, which the releases are.

   check() is also run quietly from the start screen, at most once a day (MenuActivity). */
public class UpdateActivity extends Activity {
    static final String REPO = "Cleverdee/cricket07-android";
    private static final String ACTION_INSTALLED = "com.cleverdee.cricket07.INSTALL_STATUS";
    private static final String PREFS = "updates";

    /* the latest release, when newer than this app */
    static final class Release {
        String version, notes, apkUrl;
        long apkSize;
    }

    private LinearLayout mCol;
    private TextView mStatus;
    private ProgressBar mBar;
    private Release mRelease;
    private volatile boolean mCancel;
    private boolean mBusy, mWaitingForPermission;
    private BroadcastReceiver mReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checking();
    }

    @Override
    protected void onResume() {
        super.onResume();
        /* back from the "install unknown apps" setting */
        if (mWaitingForPermission && mRelease != null) {
            mWaitingForPermission = false;
            if (getPackageManager().canRequestPackageInstalls()) download();
            else showRelease();
        }
    }

    @Override
    protected void onDestroy() {
        if (mReceiver != null) unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mBusy) { mCancel = true; return; }
        startActivity(new Intent(this, MenuActivity.class));
        finish();
    }

    /* ------------------------------------------------------------------ checking */

    static String currentVersion() { return BuildConfig.VERSION_NAME; }

    /* 1.10.0 > 1.9.2; a missing part counts as 0 */
    static boolean newer(String a, String b) {
        String[] x = a.replaceAll("^[vV]", "").split("[.-]"), y = b.replaceAll("^[vV]", "").split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    /* the latest release if it is newer than this app, else null; throws when GitHub can't be reached */
    static Release check(Context c) throws IOException {
        HttpURLConnection h = open("https://api.github.com/repos/" + REPO + "/releases/latest");
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONObject j = new JSONObject(b.toString("UTF-8"));
            c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("checked", System.currentTimeMillis()).apply();
            Release r = new Release();
            r.version = j.optString("tag_name", "").replaceAll("^[vV]", "");
            r.notes = j.optString("body", "");
            JSONArray assets = j.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                    r.apkUrl = a.optString("browser_download_url");
                    r.apkSize = a.optLong("size");
                }
            }
            return r.apkUrl != null && newer(r.version, currentVersion()) ? r : null;
        } catch (org.json.JSONException e) {
            throw new IOException("GitHub's answer couldn't be read");
        } finally {
            h.disconnect();
        }
    }

    /* the quiet daily check from the start screen: true when it's time for another */
    static boolean due(Context c) {
        long last = c.getSharedPreferences(PREFS, MODE_PRIVATE).getLong("checked", 0);
        return System.currentTimeMillis() - last > 20L * 3600 * 1000;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "Cricket07-Android/" + currentVersion());
        h.setInstanceFollowRedirects(true);   /* release downloads redirect to GitHub's file host */
        return h;
    }

    /* ------------------------------------------------------------------ screens */

    private void checking() {
        mBusy = false;
        mCol = Ui.page(this);
        Ui.title(mCol, "Updates");
        mStatus = Ui.text(mCol, "Checking for updates...", 16, Ui.SOFT);
        new Thread(() -> {
            try {
                Release r = check(this);
                runOnUiThread(() -> { mRelease = r; if (r == null) upToDate(); else showRelease(); });
            } catch (IOException e) {
                runOnUiThread(() -> problem("Couldn't reach GitHub. Check your internet connection and try again."));
            }
        }, "update-check").start();
    }

    private void upToDate() {
        mCol = Ui.page(this);
        Ui.title(mCol, "Updates");
        Ui.text(mCol, "You have the latest version (" + currentVersion() + ").", 16, Ui.SOFT);
        Ui.button(mCol, "Back", v -> onBackPressed());
    }

    private void showRelease() {
        mBusy = false;
        mCol = Ui.page(this);
        Ui.title(mCol, "Update available");
        Ui.text(mCol, "Version " + mRelease.version + " (you have " + currentVersion() + ")", 16, Ui.WHITE);
        String notes = mRelease.notes.replaceAll("\\*\\*", "").replaceAll("\\[([^]]*)\\]\\([^)]*\\)", "$1").trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "...";
        TextView t = Ui.text(mCol, notes, 13, Ui.SOFT);
        t.setGravity(android.view.Gravity.START);
        Ui.text(mCol, "Your game files, saves, mods and settings stay.", 13, Ui.DIM);
        String size = mRelease.apkSize > 0 ? String.format(java.util.Locale.US, " (%.0f MB)", mRelease.apkSize / 1e6) : "";
        Ui.button(mCol, "Update now" + size, v -> start());
        Ui.button(mCol, "Not now", true, v -> onBackPressed());
    }

    private void problem(String msg) {
        mBusy = false;
        mCol = Ui.page(this);
        Ui.title(mCol, "Updates");
        Ui.text(mCol, msg, 15, Ui.SOFT);
        Ui.button(mCol, "Try again", v -> checking());
        Ui.button(mCol, "Back", true, v -> onBackPressed());
    }

    /* ------------------------------------------------------------------ downloading and installing */

    private void start() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            mCol = Ui.page(this);
            Ui.title(mCol, "One more step");
            Ui.text(mCol, "Android asks you to allow this app to install updates. On the next screen, switch on "
                        + "\"Allow from this source\", then come back.", 15, Ui.SOFT);
            Ui.button(mCol, "Open the setting", v -> {
                mWaitingForPermission = true;
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            });
            Ui.button(mCol, "Cancel", true, v -> showRelease());
            return;
        }
        download();
    }

    private void download() {
        mBusy = true;
        mCancel = false;
        mCol = Ui.page(this);
        Ui.title(mCol, "Updating");
        mStatus = Ui.text(mCol, "Downloading version " + mRelease.version + "...", 16, Ui.SOFT);
        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(1000);
        mCol.addView(mBar, new LinearLayout.LayoutParams(Ui.dp(this, 320), Ui.dp(this, 24)));
        Ui.button(mCol, "Cancel", true, v -> mCancel = true);
        File apk = new File(getExternalCacheDir(), "update.apk");
        new Thread(() -> {
            try {
                HttpURLConnection h = open(mRelease.apkUrl);
                long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : mRelease.apkSize;
                try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int last = -1;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        if (mCancel) throw new IOException("cancelled");
                        out.write(buf, 0, n);
                        done += n;
                        final int pm = total > 0 ? (int) (done * 1000 / total) : 0;
                        if (pm != last) { last = pm; runOnUiThread(() -> { mBar.setProgress(pm); mStatus.setText("Downloading... " + pm / 10 + "%"); }); }
                    }
                } finally {
                    h.disconnect();
                }
                runOnUiThread(() -> install(apk));
            } catch (IOException e) {
                apk.delete();
                runOnUiThread(() -> { if (mCancel) showRelease(); else problem("The download stopped: " + e.getMessage()); });
            }
        }, "update-download").start();
    }

    /* hands the APK to Android's installer, which asks the player to confirm */
    private void install(File apk) {
        mStatus.setText("Installing...");
        mBar.setIndeterminate(true);
        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(confirm); }
                    } else if (st != PackageInstaller.STATUS_SUCCESS) {   /* on success Android restarts the app */
                        String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        problem(st == PackageInstaller.STATUS_FAILURE_ABORTED ? "The update was cancelled."
                              : st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                                ? "This update can't install over your version (it was built differently). Download it from "
                                  + "the GitHub releases page instead." : "The update didn't install" + (why != null ? ": " + why : "."));
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION_INSTALLED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(mReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(mReceiver, f);
        }
        new Thread(() -> {
            try {
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(getPackageName());
                p.setSize(apk.length());
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("update.apk", 0, apk.length())) {
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent cb = new Intent(ACTION_INSTALLED).setPackage(getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getBroadcast(this, 0, cb, flags).getIntentSender());
                }
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> problem("The update didn't install: " + e.getMessage()));
            }
        }, "update-install").start();
    }
}
