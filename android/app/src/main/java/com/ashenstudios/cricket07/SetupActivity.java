package com.ashenstudios.cricket07;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* The launcher. A bundled build (build.ps1 -Bundle) carries the game files as APK assets under game/, with
   game_manifest.txt listing them ("path|size|stamp" per line). They are unpacked to files/game, where the
   native code reads them, on the first launch and again for files an update changed (the manifest copied
   there records what is unpacked). A build without bundled files goes straight to the game. */
public class SetupActivity extends Activity {
    private static final String MANIFEST = "game_manifest.txt";
    private TextView mStatus;
    private ProgressBar mBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<String> wanted = readManifest();
        File dir = new File(getExternalFilesDir(null), "game");
        if (wanted == null) { startGame(); return; }
        List<String> todo = changed(wanted, dir);
        if (todo.isEmpty()) { startGame(); return; }
        showProgress();
        new Thread(() -> unpack(wanted, todo, dir), "unpack").start();
    }

    private List<String> readManifest() {
        try (InputStream in = getAssets().open(MANIFEST)) {
            return readLines(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> readLines(InputStream in) throws IOException {
        List<String> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String l; (l = r.readLine()) != null; ) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }

    /* manifest lines whose file is missing, the wrong size, or listed differently last time */
    private static List<String> changed(List<String> wanted, File dir) {
        Set<String> have = new HashSet<>();
        try (InputStream in = new java.io.FileInputStream(new File(dir, MANIFEST))) {
            have.addAll(readLines(in));
        } catch (IOException e) { /* nothing unpacked yet */ }
        List<String> todo = new ArrayList<>();
        for (String line : wanted) {
            String[] f = line.split("\\|");
            File dst = new File(dir, f[0]);
            if (!have.contains(line) || !dst.isFile() || dst.length() != Long.parseLong(f[1])) todo.add(line);
        }
        return todo;
    }

    private void unpack(List<String> wanted, List<String> todo, File dir) {
        long total = 0, done = 0;
        for (String line : todo) total += Long.parseLong(line.split("\\|")[1]);
        AssetManager am = getAssets();
        byte[] buf = new byte[1 << 20];
        try {
            File stamp = new File(dir, MANIFEST);
            stamp.delete();                         /* a half-finished unpack is redone next time */
            for (String line : todo) {
                String path = line.split("\\|")[0];
                File dst = new File(dir, path);
                dst.getParentFile().mkdirs();
                File tmp = new File(dst.getPath() + ".part");
                try (InputStream in = am.open("game/" + path, AssetManager.ACCESS_STREAMING);
                     OutputStream out = new FileOutputStream(tmp)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        report(done, total);
                    }
                }
                if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new IOException("rename " + dst); }
            }
            try (OutputStream out = new FileOutputStream(stamp)) {
                for (String line : wanted) out.write((line + "\n").getBytes("UTF-8"));
            }
            runOnUiThread(this::startGame);
        } catch (IOException e) {
            final String msg = "Couldn't unpack the game files:\n" + e.getMessage()
                             + "\n\nFree up some storage (about 1.2 GB) and open the game again.";
            runOnUiThread(() -> { mStatus.setText(msg); mBar.setVisibility(android.view.View.GONE); });
        }
    }

    private int mLastPct = -1;

    private void report(long done, long total) {
        final int pct = total > 0 ? (int) (done * 100 / total) : 100;
        if (pct == mLastPct) return;
        mLastPct = pct;
        runOnUiThread(() -> { mBar.setProgress(pct); mStatus.setText("Preparing game files... " + pct + "%"); });
    }

    private void showProgress() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(12, 28, 18));
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        mStatus = new TextView(this);
        mStatus.setText("Preparing game files... (first launch only)");
        mStatus.setTextColor(Color.rgb(200, 220, 205));
        mStatus.setTextSize(16);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(0, pad / 2, 0, pad / 2);
        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(100);
        root.addView(title);
        root.addView(mStatus);
        root.addView(mBar, new LinearLayout.LayoutParams((int) (360 * getResources().getDisplayMetrics().density),
                                                         ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void startGame() {
        /* a game already running (the launcher was tapped again) is resumed; otherwise the start screen */
        startActivity(new Intent(this, GameActivity.sRunning ? GameActivity.class : MenuActivity.class));
        finish();
        overridePendingTransition(0, 0);
    }
}
