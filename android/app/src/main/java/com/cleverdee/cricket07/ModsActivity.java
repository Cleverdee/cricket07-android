package com.cleverdee.cricket07;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/* The Mods screen: add a mod (a .zip laid out like the game folder, or a single game file such as data.gob), switch
   mods on and off, remove them. The changes reach the game folder when the screen is left (Mods.sync). */
public class ModsActivity extends Activity {
    private static final int PICK = 1;
    private LinearLayout mCol;
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (!mBusy) done();
    }

    private void showList() {
        mBusy = false;
        mCol = Ui.page(this);
        Ui.title(mCol, "Mods");
        List<Mods.Mod> mods = Mods.list(this);
        if (mods.isEmpty())
            Ui.text(mCol, "Mods swap game files for other people's versions: new rosters, kits, commentary. Add one as a "
                        + ".zip, or as a single game file such as data.gob. Your original files are kept, and a mod can be "
                        + "switched off at any time.", 14, Ui.SOFT);
        else
            Ui.text(mCol, "Tap a mod to switch it on or off, or to remove it. When two mods change the same file, the "
                        + "one added last wins.", 13, Ui.DIM);
        for (Mods.Mod m : mods) {
            Ui.MenuButton b = Ui.button(mCol, m.name, v -> showMod(m));
            b.setNote(m.on ? "ON" : "OFF");
        }
        Ui.button(mCol, "Add a mod", v -> pick());
        Ui.button(mCol, "Done", true, v -> done());
        Ui.text(mCol, "Both phones need the same mods to play multiplayer.", 12, Ui.DIM);
    }

    private void showMod(Mods.Mod m) {
        mCol = Ui.page(this);
        Ui.heading(mCol, m.name);
        StringBuilder s = new StringBuilder("Changes ");
        s.append(m.paths.size() == 1 ? m.paths.get(0) : m.paths.size() + " game files");
        for (Mods.Mod o : Mods.list(this)) {
            if (o.id.equals(m.id) || !o.on || !m.on) continue;
            for (String p : o.paths) if (m.paths.contains(p)) { s.append("\nAlso changed by ").append(o.name); break; }
        }
        Ui.text(mCol, s.toString(), 14, Ui.SOFT);
        Ui.button(mCol, m.on ? "Switch off" : "Switch on", v -> { setOn(m, !m.on); showList(); });
        Ui.button(mCol, "Remove", true, v -> confirmRemove(m));
        Ui.button(mCol, "Back", true, v -> showList());
    }

    private void confirmRemove(Mods.Mod m) {
        mCol = Ui.page(this);
        Ui.heading(mCol, "Remove " + m.name + "?");
        Ui.text(mCol, "Its files are deleted from the app and the game's own come back.", 14, Ui.SOFT);
        Ui.button(mCol, "Remove", v -> work("Removing...", () -> { Mods.remove(this, m); return null; }));
        Ui.button(mCol, "Cancel", true, v -> showList());
    }

    private void setOn(Mods.Mod m, boolean on) {
        List<Mods.Mod> mods = Mods.list(this);
        for (Mods.Mod x : mods) if (x.id.equals(m.id)) x.on = on;
        try { Mods.save(this, mods); } catch (IOException e) { problem(e.getMessage()); }
    }

    /* leaving: the game folder is brought in line with the list */
    private void done() {
        work("Applying mods...", () -> { Mods.sync(this, true); return null; }, true);
    }

    /* ------------------------------------------------------------------ adding */

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, PICK); }
        catch (android.content.ActivityNotFoundException e) { problem("This phone has no file picker."); }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        work("Adding the mod...", () -> addFrom(uri));
    }

    private String addFrom(Uri uri) throws Exception {
        String name = "mod";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst()) { if (!c.isNull(0)) name = c.getString(0); if (!c.isNull(1)) size = c.getLong(1); }
        }
        File root = Mods.root(this);
        root.mkdirs();
        long free = new StatFs(root.getPath()).getAvailableBytes();
        if (size > 0 && free < size * 2 + (100L << 20))
            throw new IllegalArgumentException(String.format(Locale.US, "Not enough free space: this mod needs about %.1f GB.", size * 2 / 1e9));
        File stage = new File(root, "stage");
        SetupActivity.deleteTree(stage);
        stage.mkdirs();
        try {
            byte[] buf = new byte[1 << 20];
            String lower = name.toLowerCase(Locale.US);
            if (lower.endsWith(".rar") || lower.endsWith(".7z"))
                throw new IllegalArgumentException("That's a ." + lower.substring(lower.lastIndexOf('.') + 1)
                                                   + " file: unpack it, or ask for a .zip.");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("can't open " + name);
                if (lower.endsWith(".zip")) {
                    try (ZipInputStream z = new ZipInputStream(in)) {
                        for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                            String p = e.getName().replace('\\', '/');
                            if (e.isDirectory() || p.contains("../") || p.startsWith("/")) continue;
                            File dst = new File(stage, p);
                            dst.getParentFile().mkdirs();
                            copy(z, dst, buf);
                        }
                    }
                } else {
                    copy(in, new File(stage, name), buf);
                }
            }
            String title = name.replaceAll("\\.[A-Za-z0-9]{1,4}$", "");
            return Mods.add(this, stage, title);
        } finally {
            SetupActivity.deleteTree(stage);
        }
    }

    private static void copy(InputStream in, File dst, byte[] buf) throws IOException {
        try (OutputStream out = new FileOutputStream(dst)) {
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }

    /* ------------------------------------------------------------------ background work */

    interface Job { String run() throws Exception; }

    private void work(String what, Job job) { work(what, job, false); }

    /* runs job off the UI thread behind a progress screen; a returned message is shown, then the list (or, when
       leaving, the start screen) */
    private void work(String what, Job job, boolean leave) {
        mBusy = true;
        mCol = Ui.page(this);
        Ui.title(mCol, "Mods");
        Ui.text(mCol, what, 16, Ui.SOFT);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        mCol.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 300), Ui.dp(this, 24)));
        new Thread(() -> {
            String msg;
            boolean ok = true;
            try { msg = job.run(); }
            catch (IllegalArgumentException e) { msg = e.getMessage(); ok = false; }
            catch (Exception e) { msg = "That didn't work: " + e.getMessage(); ok = false; }
            final String m = msg;
            final boolean good = ok;
            runOnUiThread(() -> {
                mBusy = false;
                if (leave && good) { startActivity(new Intent(this, MenuActivity.class)); finish(); }
                else if (m != null) message(good ? "Mod added" : "Couldn't do that", m);
                else showList();
            });
        }, "mods").start();
    }

    private void message(String title, String msg) {
        mCol = Ui.page(this);
        Ui.heading(mCol, title);
        TextView t = Ui.text(mCol, msg, 13, Ui.SOFT);
        t.setGravity(android.view.Gravity.START);
        Ui.button(mCol, "OK", v -> showList());
    }

    private void problem(String msg) { message("Couldn't do that", msg); }
}
