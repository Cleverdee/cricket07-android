package com.cleverdee.cricket07;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/* The launcher. Gets the game files into files/game (Android/data/<package>/files/game), where the native code
   reads them, then starts the game.

   A release APK carries no game files: on first launch (or from the start screen's "Game files") the player picks
   their own copy with Android's file picker, either a .zip of the installed PC game folder or the folder itself,
   and it is copied in. import_manifest.txt (path|size|crc32) is written beside it, for MenuActivity.buildId.
   Files copied there by other means (a PC over USB, build.ps1 -PushGame) work too.

   A bundled build (build.ps1 -Bundle) carries the game files as APK assets under game/, with game_manifest.txt
   listing them ("path|size|stamp" per line). They are unpacked on the first launch and again for files an update
   changed (the manifest copied there records what is unpacked). */
public class SetupActivity extends Activity {
    static final String EXTRA_MANAGE = "manage";   /* opened from the start screen: show the game-files screen */
    static final String IMPORTED = "import_manifest.txt";
    private static final String MANIFEST = "game_manifest.txt";
    private static final String EXE = "cricket07.exe";
    private static final int PICK_ZIP = 1, PICK_FOLDER = 2;
    private static final long SPARE_BYTES = 200L << 20;   /* left free after copying */
    private static final int SOFT = Color.rgb(200, 220, 205), DIM = Color.rgb(150, 175, 160);

    private LinearLayout mRoot;
    private TextView mStatus;
    private ProgressBar mBar;
    private float mDp;
    private volatile boolean mCancel;
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDp = getResources().getDisplayMetrics().density;
        List<String> wanted = readManifest();
        File dir = gameDir();
        if (wanted != null) {
            List<String> todo = changed(wanted, dir);
            if (todo.isEmpty()) { startGame(); return; }
            showProgress("Preparing game files... (first launch only)", false);
            new Thread(() -> unpack(wanted, todo, dir), "unpack").start();
            return;
        }
        if (hasGame() && !getIntent().getBooleanExtra(EXTRA_MANAGE, false)) { startGame(); return; }
        showWelcome();
    }

    private File gameDir() { return new File(getExternalFilesDir(null), "game"); }

    private boolean hasGame() { return new File(gameDir(), EXE).isFile(); }

    @Override
    public void onBackPressed() {
        if (mBusy) { mCancel = true; return; }
        if (getIntent().getBooleanExtra(EXTRA_MANAGE, false) && hasGame()) startGame();
        else super.onBackPressed();
    }

    /* ------------------------------------------------------------------ screens */

    private void page() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.rgb(12, 28, 18));
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setGravity(Gravity.CENTER);
        int pad = (int) (24 * mDp);
        mRoot.setPadding(pad, pad, pad, pad);
        sv.addView(mRoot);
        setContentView(sv);
        mBar = null;
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (5 * mDp), 0, (int) (5 * mDp));
        t.setMaxWidth((int) (560 * mDp));
        mRoot.addView(t);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (340 * mDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * mDp);
        mRoot.addView(b, lp);
        return b;
    }

    private void showWelcome() {
        mBusy = false;
        page();
        boolean have = hasGame();
        text(getString(R.string.app_name), 28, Color.WHITE);
        if (have) {
            text("Game files are installed.", 16, SOFT);
            button("Play", v -> startGame());
            text("Replace them with another copy:", 14, DIM);
        } else {
            text("This app needs the game files from your own PC copy of EA Sports Cricket 07.", 16, SOFT);
        }
        button("Choose a .zip file", v -> pick(PICK_ZIP));
        button("Choose a folder", v -> pick(PICK_FOLDER));
        button("How do I get the files?", v -> showHelp());
        if (have) button("Delete game files", v -> confirmDelete());
        text("They're copied into the app (about 1.2 GB). Your saves are kept.", 13, DIM);
    }

    private void showHelp() {
        page();
        text("Getting the game files", 24, Color.WHITE);
        text("1. On your PC, open the folder Cricket 07 is installed in. It's usually\n"
           + "C:\\Program Files (x86)\\EA SPORTS\\Cricket 07\n"
           + "and has cricket07.exe and the Audio and Data folders in it.", 15, SOFT);
        text("2. Right-click that folder, then Send to > Compressed (zipped) folder.\n"
           + "On Windows 11: right-click > Compress to > ZIP File.", 15, SOFT);
        text("3. Copy the .zip to your phone: with a USB cable into Download, or through Google Drive, "
           + "a memory card, or any app you like.", 15, SOFT);
        text("4. Come back here, tap \"Choose a .zip file\" and pick it.", 15, SOFT);
        text("You can also copy the whole folder to the phone without zipping it and use \"Choose a folder\". "
           + "The game disc and its installer won't work: it has to be the installed game.", 13, DIM);
        button("Back", v -> showWelcome());
    }

    private void confirmDelete() {
        page();
        text("Delete the game files?", 22, Color.WHITE);
        text("This frees about 1.2 GB. Your saves and settings stay, and you can add the files again later.", 15, SOFT);
        button("Delete", v -> { deleteTree(gameDir()); showWelcome(); });
        button("Cancel", v -> showWelcome());
    }

    private void showProgress(String status, boolean cancellable) {
        mBusy = true;
        mCancel = false;
        page();
        text(getString(R.string.app_name), 28, Color.WHITE);
        mStatus = text(status, 16, SOFT);
        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setMax(1000);
        mRoot.addView(mBar, new LinearLayout.LayoutParams((int) (360 * mDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        if (cancellable) button("Cancel", v -> mCancel = true);
    }

    private void showError(String msg) {
        mBusy = false;
        page();
        text("Couldn't add the game files", 22, Color.WHITE);
        text(msg, 15, SOFT);
        button("Try again", v -> showWelcome());
        button("How do I get the files?", v -> showHelp());
    }

    /* ------------------------------------------------------------------ picking */

    private void pick(int what) {
        Intent i;
        if (what == PICK_ZIP) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");   /* file managers label zips in different ways: the name is checked after picking */
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/x-zip-compressed",
                                                                "application/x-zip", "application/octet-stream" });
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        try {
            startActivityForResult(i, what);
        } catch (android.content.ActivityNotFoundException e) {
            showError("This phone has no file picker for that. Try the other option.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        showProgress("Looking at the files...", true);
        new Thread(() -> {
            try {
                if (request == PICK_ZIP) importZip(uri);
                else importFolder(uri);
            } catch (Cancelled e) {
                runOnUiThread(this::showWelcome);
            } catch (Problem e) {
                runOnUiThread(() -> showError(e.getMessage()));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> showError("Reading the files failed: " + e.getMessage()));
            } finally {
                deleteTree(stagingDir());
            }
        }, "import").start();
    }

    private static class Problem extends Exception { Problem(String m) { super(m); } }
    private static class Cancelled extends IOException { }

    private File stagingDir() { return new File(getExternalFilesDir(null), "game.import"); }

    private void checkSpace(long need) throws Problem {
        long free = new StatFs(getExternalFilesDir(null).getPath()).getAvailableBytes();
        if (free < need + SPARE_BYTES)
            throw new Problem(String.format(Locale.US, "Not enough free space: this needs about %.1f GB and the phone has %.1f GB free.",
                                            (need + SPARE_BYTES) / 1e9, free / 1e9));
    }

    /* ------------------------------------------------------------------ copying: a .zip */

    private void importZip(Uri uri) throws IOException, Problem {
        ContentResolver cr = getContentResolver();
        String name = "";
        long size = -1;
        try (Cursor c = cr.query(uri, new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.isNull(0) ? "" : c.getString(0);
                size = c.isNull(1) ? -1 : c.getLong(1);
            }
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".iso"))
            throw new Problem(name + " isn't a .zip. Make a .zip of the installed game folder (see How do I get the files?).");
        if (size > 0) checkSpace(size + size / 20);   /* the game's files hardly compress */
        File stage = stagingDir();
        deleteTree(stage);
        stage.mkdirs();
        List<String[]> copied = new ArrayList<>();   /* staging-relative path, size, crc */
        final long total = size;
        try (CountingStream counted = new CountingStream(cr.openInputStream(uri));
             ZipInputStream zin = new ZipInputStream(counted)) {
            byte[] buf = new byte[1 << 20];
            ZipEntry e;
            boolean any = false;
            while ((e = zin.getNextEntry()) != null) {
                any = true;
                String path = e.getName().replace('\\', '/');
                if (e.isDirectory() || path.contains("../") || path.startsWith("/")) continue;
                File dst = new File(stage, path);
                dst.getParentFile().mkdirs();
                CRC32 crc = new CRC32();
                long n = copyStream(zin, dst, buf, crc, () -> progress("Copying the game files...", counted.count, total));
                copied.add(new String[] { path, String.valueOf(n), Long.toHexString(crc.getValue()) });
            }
            if (!any) throw new Problem((name.isEmpty() ? "That file" : name) + " couldn't be read as a .zip file.");
        }
        final String shown = name;
        install(stage, copied, () -> askDeleteSource(uri, shown));
    }

    /* ------------------------------------------------------------------ copying: a folder */

    private static class Doc {
        final String id, name, mime;
        final long size;
        Doc(String i, String n, String m, long s) { id = i; name = n; mime = m; size = s; }
        boolean isDir() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }
    }

    private List<Doc> children(Uri tree, String id) {
        List<Doc> out = new ArrayList<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] cols = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                          DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE };
        try (Cursor c = getContentResolver().query(u, cols, null, null, null)) {
            while (c != null && c.moveToNext())
                out.add(new Doc(c.getString(0), c.getString(1), c.getString(2), c.isNull(3) ? 0 : c.getLong(3)));
        }
        return out;
    }

    /* the folder holding cricket07.exe: the picked one, or one up to three levels inside it */
    private String findGameRoot(Uri tree, String id, int depth) {
        List<Doc> kids = children(tree, id);
        for (Doc d : kids) if (!d.isDir() && d.name.equalsIgnoreCase(EXE)) return id;
        if (depth == 0) return null;
        for (Doc d : kids) if (d.isDir()) { String r = findGameRoot(tree, d.id, depth - 1); if (r != null) return r; }
        return null;
    }

    private long treeSize(Uri tree, String id) throws Cancelled {
        if (mCancel) throw new Cancelled();
        long n = 0;
        for (Doc d : children(tree, id)) n += d.isDir() ? treeSize(tree, d.id) : d.size;
        return n;
    }

    private void importFolder(Uri tree) throws IOException, Problem {
        String root = findGameRoot(tree, DocumentsContract.getTreeDocumentId(tree), 3);
        if (root == null)
            throw new Problem("There's no cricket07.exe in that folder. Choose the folder Cricket 07 is installed in "
                            + "(the one with cricket07.exe, Audio and Data).");
        long total = treeSize(tree, root);
        checkSpace(total);
        File stage = stagingDir();
        deleteTree(stage);
        stage.mkdirs();
        List<String[]> copied = new ArrayList<>();
        copyTree(tree, root, stage, "", copied, new long[1], total, new byte[1 << 20]);
        install(stage, copied, this::startGame);
    }

    private void copyTree(Uri tree, String id, File dir, String rel, List<String[]> copied, long[] done, long total,
                          byte[] buf) throws IOException {
        for (Doc d : children(tree, id)) {
            String path = rel.isEmpty() ? d.name : rel + "/" + d.name;
            if (d.isDir()) { copyTree(tree, d.id, dir, path, copied, done, total, buf); continue; }
            File dst = new File(dir, path);
            dst.getParentFile().mkdirs();
            CRC32 crc = new CRC32();
            final long base = done[0];
            long n;
            try (CountingStream in = new CountingStream(
                     getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id)))) {
                n = copyStream(in, dst, buf, crc, () -> progress("Copying the game files...", base + in.count, total));
            }
            done[0] += n;
            copied.add(new String[] { path, String.valueOf(n), Long.toHexString(crc.getValue()) });
        }
    }

    /* ------------------------------------------------------------------ checking and moving into place */

    /* Finds the game folder inside the staging copy, checks it, writes its manifest and swaps it in for files/game. */
    private void install(File stage, List<String[]> copied, Runnable after) throws IOException, Problem {
        File exe = findExe(stage, 4);
        if (exe == null)
            throw new Problem("There's no cricket07.exe in it. Zip the folder Cricket 07 is installed in "
                            + "(the one with cricket07.exe, Audio and Data).");
        File root = exe.getParentFile();
        if (!exe.getName().equals(EXE) && !exe.renameTo(new File(root, EXE))) throw new IOException("rename " + exe);
        if (findChild(findChild(root, "Data"), "data.gob") == null)
            throw new Problem("The Data folder (with data.gob) isn't next to cricket07.exe. Copy the whole installed game folder.");
        progress("Checking the game version...", 0, -1);
        String want = BuildConfig.EXE_SHA256;
        boolean known = want.isEmpty() || sha256(new File(root, EXE)).startsWith(want);

        String prefix = root.equals(stage) ? "" : stage.toURI().relativize(root.toURI()).getPath();
        StringBuilder m = new StringBuilder();
        for (String[] f : copied) {
            if (!f[0].startsWith(prefix)) continue;
            String p = f[0].substring(prefix.length());
            if (p.equalsIgnoreCase(EXE)) p = EXE;
            m.append(p).append('|').append(f[1]).append('|').append(f[2]).append('\n');
        }
        try (OutputStream out = new FileOutputStream(new File(root, IMPORTED))) { out.write(m.toString().getBytes("UTF-8")); }

        File dir = gameDir();
        deleteTree(dir);
        if (!root.renameTo(dir)) throw new IOException("couldn't move the files into place");
        runOnUiThread(() -> {
            if (known) after.run();
            else showVersionWarning(after);
        });
    }

    private void showVersionWarning(Runnable after) {
        mBusy = false;
        page();
        text("A different version of the game", 22, Color.WHITE);
        text("This cricket07.exe isn't the version this app was made from, so the game will probably not start. "
           + "The app works with the original PC release. Other versions can be made into an app on a PC with "
           + "Cricket07Builder.exe (see the project page).", 15, SOFT);
        button("Try it anyway", v -> after.run());
        button("Choose other files", v -> showWelcome());
    }

    private void askDeleteSource(Uri uri, String name) {
        mBusy = false;
        page();
        text("Game files added", 24, Color.WHITE);
        text("You can delete " + (name.isEmpty() ? "the .zip" : name) + " now to free up space. The game doesn't need it any more.",
             15, SOFT);
        button("Delete it and play", v -> {
            boolean gone = false;
            try { gone = DocumentsContract.deleteDocument(getContentResolver(), uri); }
            catch (Exception e) { /* the file's app doesn't allow it */ }
            if (!gone) Toast.makeText(this, "Couldn't delete it from here: delete it in your file manager", Toast.LENGTH_LONG).show();
            startGame();
        });
        button("Keep it and play", v -> startGame());
    }

    private static File findExe(File dir, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) if (k.isFile() && k.getName().equalsIgnoreCase(EXE)) return k;
        if (depth == 0) return null;
        for (File k : kids) if (k.isDirectory()) { File r = findExe(k, depth - 1); if (r != null) return r; }
        return null;
    }

    private static File findChild(File dir, String name) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids != null) for (File k : kids) if (k.getName().equalsIgnoreCase(name)) return k;
        return null;
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte b : md.digest()) s.append(String.format("%02x", b));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private long copyStream(InputStream in, File dst, byte[] buf, CRC32 crc, Runnable tick) throws IOException {
        long n = 0;
        try (OutputStream out = new FileOutputStream(dst)) {
            for (int r; (r = in.read(buf)) > 0; ) {
                if (mCancel) throw new Cancelled();
                out.write(buf, 0, r);
                crc.update(buf, 0, r);
                n += r;
                tick.run();
            }
        }
        return n;
    }

    private static class CountingStream extends FilterInputStream {
        volatile long count;
        CountingStream(InputStream in) throws IOException {
            super(in);
            if (in == null) throw new IOException("can't open the file");
        }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) count++; return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
        @Override public long skip(long n) throws IOException { long s = super.skip(n); count += s; return s; }
    }

    /* ------------------------------------------------------------------ bundled builds */

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
        try (InputStream in = new FileInputStream(new File(dir, MANIFEST))) {
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
                        progress("Preparing game files...", done, total);
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
            runOnUiThread(() -> { mStatus.setText(msg); if (mBar != null) mBar.setVisibility(View.GONE); });
        }
    }

    /* ------------------------------------------------------------------ shared */

    private int mLastPermille = -2;

    /* from any thread; total < 0: size unknown (the bar just shows activity) */
    private void progress(String what, long done, long total) {
        final int pm = total > 0 ? (int) Math.min(1000, done * 1000 / total) : -1;
        if (pm == mLastPermille) return;
        mLastPermille = pm;
        final String s = pm >= 0 ? what + " " + pm / 10 + "%" : what;
        runOnUiThread(() -> {
            if (mBar == null) return;
            mBar.setIndeterminate(pm < 0);
            if (pm >= 0) mBar.setProgress(pm);
            mStatus.setText(s);
        });
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void startGame() {
        /* a game already running (the launcher was tapped again) is resumed; otherwise the start screen */
        startActivity(new Intent(this, GameActivity.sRunning ? GameActivity.class : MenuActivity.class));
        finish();
        overridePendingTransition(0, 0);
    }
}
