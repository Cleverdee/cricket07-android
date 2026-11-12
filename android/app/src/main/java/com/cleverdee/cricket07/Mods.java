package com.cleverdee.cricket07;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* Mods: other people's versions of game files (a data.gob with new rosters, commentary in Audio/, ...), switched on
   and off without ever losing the originals.

   files/mods/
     list.txt          one line per mod, in the order added: id|1 (on) or id|0 (off)
     <id>/name.txt     the mod's name
     <id>/files/...    its files, by the game-folder path they replace (or add)
     orig/...          originals moved out of the game folder while a mod's file is in
     applied.txt       path|id for every game file currently replaced
     journal.txt       a change in progress (see sync)
     launching         the game was started with mods in and hasn't reached its menu yet

   Files are only ever renamed (all of this is on one file system): a 700 MB data.gob is swapped in at once and costs
   no extra space. A mod's file lives either in <id>/files or, while applied, in the game folder.

   sync() works out which mod wins each path (the last one added, among those on), and writes the renames it needs
   to journal.txt before doing any. If the app dies part way, the next sync() finishes them (a rename is done when
   its source is gone and its target exists), so the folders never stay half changed. */
final class Mods {
    static final class Mod {
        String id, name;
        boolean on;
        final List<String> paths = new ArrayList<>();   /* game-folder paths it replaces or adds */
    }

    private Mods() {}

    static File root(Context c) { return new File(c.getExternalFilesDir(null), "mods"); }
    static File game(Context c) { return new File(c.getExternalFilesDir(null), "game"); }

    /* ------------------------------------------------------------------ the list */

    static List<Mod> list(Context c) {
        List<Mod> out = new ArrayList<>();
        Map<String, String> applied = readMap(new File(root(c), "applied.txt"));
        for (String line : readLines(new File(root(c), "list.txt"))) {
            String[] f = line.split("\\|");
            if (f.length < 2) continue;
            Mod m = new Mod();
            m.id = f[0];
            m.on = f[1].equals("1");
            List<String> name = readLines(new File(new File(root(c), m.id), "name.txt"));
            m.name = name.isEmpty() ? m.id : name.get(0);
            listFiles(new File(new File(root(c), m.id), "files"), "", m.paths);
            for (Map.Entry<String, String> e : applied.entrySet())   /* its files that are in the game folder now */
                if (e.getValue().equals(m.id) && !m.paths.contains(e.getKey())) m.paths.add(e.getKey());
            out.add(m);
        }
        return out;
    }

    static void save(Context c, List<Mod> mods) throws IOException {
        StringBuilder s = new StringBuilder();
        for (Mod m : mods) s.append(m.id).append('|').append(m.on ? '1' : '0').append('\n');
        write(new File(root(c), "list.txt"), s.toString());
    }

    static boolean anyApplied(Context c) { return !readMap(new File(root(c), "applied.txt")).isEmpty(); }

    /* ------------------------------------------------------------------ applying */

    /* Puts the game folder in the state the list asks for; active = false takes every mod out (before the game files
       are replaced or deleted). */
    static synchronized void sync(Context c, boolean active) throws IOException {
        File root = root(c), game = game(c), orig = new File(root, "orig");
        File journal = new File(root, "journal.txt"), appliedFile = new File(root, "applied.txt");
        if (journal.isFile()) finish(journal, appliedFile);

        Map<String, String> applied = readMap(appliedFile);
        Map<String, String> want = new LinkedHashMap<>();
        if (active && new File(game, "cricket07.exe").isFile())
            for (Mod m : list(c)) if (m.on) for (String p : m.paths) want.put(p, m.id);   /* later mods win */

        List<String[]> ops = new ArrayList<>();
        for (Map.Entry<String, String> e : applied.entrySet()) {
            String p = e.getKey(), id = e.getValue();
            if (id.equals(want.get(p))) continue;
            ops.add(new String[] { new File(game, p).getPath(), modFile(c, id, p).getPath() });
            if (new File(orig, p).exists()) ops.add(new String[] { new File(orig, p).getPath(), new File(game, p).getPath() });
        }
        Map<String, String> next = new LinkedHashMap<>(applied);
        next.keySet().removeIf(p -> !applied.get(p).equals(want.get(p)));
        for (Map.Entry<String, String> e : want.entrySet()) {
            String p = e.getKey(), id = e.getValue();
            if (id.equals(applied.get(p))) continue;
            File src = modFile(c, id, p);
            boolean replacing = new File(game, p).exists() && (applied.get(p) == null || !applied.get(p).equals(id));
            if (!src.isFile()) continue;   /* the mod lost the file somehow: leave the game's own */
            if (replacing || (applied.containsKey(p) && !applied.get(p).equals(id)))
                ops.add(new String[] { new File(game, p).getPath(), new File(orig, p).getPath() });
            ops.add(new String[] { src.getPath(), new File(game, p).getPath() });
            next.put(p, id);
        }
        if (ops.isEmpty()) return;

        StringBuilder j = new StringBuilder();
        for (String[] op : ops) j.append("mv\t").append(op[0]).append('\t').append(op[1]).append('\n');
        for (Map.Entry<String, String> e : next.entrySet()) j.append("ap\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        write(journal, j.toString());
        finish(journal, appliedFile);
    }

    /* does (or finishes) the renames in a journal, then records the new applied list and drops the journal */
    private static void finish(File journal, File appliedFile) throws IOException {
        StringBuilder applied = new StringBuilder();
        for (String line : readLines(journal)) {
            String[] f = line.split("\t");
            if (f.length < 3) continue;
            if (f[0].equals("ap")) { applied.append(f[1]).append('|').append(f[2]).append('\n'); continue; }
            File from = new File(f[1]), to = new File(f[2]);
            if (!from.exists()) continue;                      /* done already */
            to.getParentFile().mkdirs();
            if (to.exists()) throw new IOException("both " + from + " and " + to + " exist; left as they are");
            if (!from.renameTo(to)) throw new IOException("can't move " + from);
        }
        write(appliedFile, applied.toString());
        journal.delete();
    }

    private static File modFile(Context c, String id, String path) {
        return new File(new File(new File(root(c), id), "files"), path);
    }

    /* ------------------------------------------------------------------ adding and removing */

    /* Paths a mod may never touch: the game code was translated from this exe, and the app's own records. */
    static String refused(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".exe") || p.endsWith(".dll")) return "program files can't be modded (the app is built from this exe)";
        if (p.equals(SetupActivity.IMPORTED) || p.equals("game_manifest.txt")) return "app file";
        return null;
    }

    /* A .gob or .big must be a whole, readable EA archive: a cut-off download would crash the game. Returns why not, or null. */
    static String checkArchive(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[16];
            r.readFully(h);
            String magic = new String(h, 0, 4, StandardCharsets.ISO_8859_1);
            if (!magic.equals("BIGF") && !magic.equals("BIG4")) return "isn't an EA archive";
            int count = be32(h, 8);
            long len = r.length();
            if (count <= 0 || count > 1_000_000) return "has a broken file list";
            byte[] idx = new byte[(int) Math.min(len - 16, (long) count * 300 + 16)];
            r.readFully(idx);
            int pos = 0;
            for (int i = 0; i < count; i++) {
                if (pos + 9 > idx.length) return "has a broken file list";
                long off = be32(idx, pos) & 0xFFFFFFFFL, size = be32(idx, pos + 4) & 0xFFFFFFFFL;
                if (off + size > len) return "is cut off (an incomplete download?)";
                pos += 8;
                while (pos < idx.length && idx[pos] != 0) pos++;
                pos++;
            }
            return null;
        } catch (IOException e) {
            return "can't be read";
        }
    }

    private static int be32(byte[] b, int o) {
        return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255);
    }

    /* Matches the files of an unpacked mod (staged) to game-folder paths and moves them into a new mod. A file goes
       where the game has a file whose path is the longest match for the end of its path (Data/data.gob,
       or a bare data.gob); files the game doesn't have are added only under a recognisable game folder (one with
       Data or Audio in it). Returns a message about what was left out, or throws with why nothing could be used. */
    static String add(Context c, File staged, String name) throws IOException, IllegalArgumentException {
        Map<String, String> gameFiles = new HashMap<>();   /* lower-case path -> path as in the game folder */
        List<String> all = new ArrayList<>();
        listFiles(game(c), "", all);
        Map<String, String> applied = readMap(new File(root(c), "applied.txt"));
        for (String p : all) gameFiles.put(p.toLowerCase(Locale.US), p);
        for (String p : applied.keySet()) gameFiles.put(p.toLowerCase(Locale.US), p);

        List<String> files = new ArrayList<>();
        listFiles(staged, "", files);
        String base = gameRoot(staged, "");
        Map<String, String> target = new LinkedHashMap<>();   /* staged path -> game path */
        List<String> skipped = new ArrayList<>();
        for (String s : files) {
            String t = match(s, gameFiles);
            if (t == null && base != null && s.startsWith(base)) t = s.substring(base.length());
            String why = t == null ? "not a game file" : refused(t);
            if (why == null && target.containsValue(t)) why = "twice in the mod";
            if (why == null && (t.toLowerCase(Locale.US).endsWith(".gob") || t.toLowerCase(Locale.US).endsWith(".big")))
                why = checkArchive(new File(staged, s));
            if (why != null) { skipped.add(s + ": " + why); continue; }
            target.put(s, t);
        }
        if (target.isEmpty())
            throw new IllegalArgumentException(skipped.isEmpty() ? "It's empty." : "Nothing in it replaces a game file:\n" + join(skipped, 6));

        String id = "m" + Long.toString(System.currentTimeMillis(), 36);
        File dir = new File(root(c), id), out = new File(dir, "files");
        for (Map.Entry<String, String> e : target.entrySet()) {
            File dst = new File(out, e.getValue());
            dst.getParentFile().mkdirs();
            if (!new File(staged, e.getKey()).renameTo(dst)) throw new IOException("can't store " + e.getValue());
        }
        write(new File(dir, "name.txt"), name + "\n");
        List<Mod> mods = list(c);
        Mod m = new Mod();
        m.id = id; m.on = true;
        mods.add(m);
        save(c, mods);
        return skipped.isEmpty() ? null : "Left out:\n" + join(skipped, 6);
    }

    static void remove(Context c, Mod m) throws IOException {
        List<Mod> mods = list(c);
        for (Mod x : mods) if (x.id.equals(m.id)) x.on = false;
        save(c, mods);
        sync(c, true);   /* its files come out of the game folder first */
        mods.removeIf(x -> x.id.equals(m.id));
        save(c, mods);
        SetupActivity.deleteTree(new File(root(c), m.id));
    }

    /* the game-folder path the end of staged path s matches best, or null */
    private static String match(String s, Map<String, String> gameFiles) {
        String[] parts = s.split("/");
        for (int i = 0; i < parts.length; i++) {
            StringBuilder b = new StringBuilder();
            for (int k = i; k < parts.length; k++) b.append(k > i ? "/" : "").append(parts[k]);
            String hit = gameFiles.get(b.toString().toLowerCase(Locale.US));
            if (hit != null) return hit;
        }
        return null;
    }

    /* the folder inside an unpacked mod that stands for the game folder: the one holding Data or Audio */
    private static String gameRoot(File dir, String rel) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids)
            if (k.isDirectory() && (k.getName().equalsIgnoreCase("Data") || k.getName().equalsIgnoreCase("Audio"))) return rel;
        for (File k : kids)
            if (k.isDirectory()) { String r = gameRoot(k, rel + k.getName() + "/"); if (r != null) return r; }
        return null;
    }

    /* ------------------------------------------------------------------ crash guard */

    static File launching(Context c) { return new File(root(c), "launching"); }

    /* ------------------------------------------------------------------ small file helpers */

    static void listFiles(File dir, String rel, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) listFiles(k, rel + k.getName() + "/", out);
            else out.add(rel + k.getName());
        }
    }

    static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) if (!l.trim().isEmpty()) out.add(l.trim());
        } catch (IOException e) { /* none yet */ }
        return out;
    }

    private static Map<String, String> readMap(File f) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : readLines(f)) { int i = l.lastIndexOf('|'); if (i > 0) m.put(l.substring(0, i), l.substring(i + 1)); }
        return m;
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("can't write " + f); }
    }

    private static String join(List<String> l, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) b.append(l.get(i)).append('\n');
        if (l.size() > max) b.append("... and ").append(l.size() - max).append(" more");
        return b.toString().trim();
    }
}
