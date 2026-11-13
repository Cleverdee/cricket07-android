package com.cleverdee.cricket07;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

/* The start screen: play alone, or set up a match between two phones on the same network (Wi-Fi or one phone's
   hotspot). Both phones then start the game fresh in lockstep (host/netplay.c, host/netplay_udp.c).

   The lobby speaks small text packets on UDP port 47006:
     CK07?                       joiner, broadcast: any hosts?
     CK07!|name|build            host's answer
     CK07J|name|build            joiner: let me in
     CK07X                       host: not the same build
     CK07P|n / CK07Q|n           host's ping, joiner's echo (to choose the input delay)
     CK07S|session|delay         host: start
     CK07A                       joiner: starting
   "build" covers the game code and data: lockstep only works between identical copies. */
public class MenuActivity extends Activity {
    private static final int LOBBY_PORT = 47006, GAME_PORT = 47007;

    private LinearLayout mRoot;
    private volatile boolean mStop;
    private volatile DatagramSocket mSock;
    private WifiManager.MulticastLock mMulticast;
    private float mDp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDp = getResources().getDisplayMetrics().density;
        mRoot = Ui.page(this);
        /* mods were in and the game never reached its menu: offer to take them out */
        boolean modTrouble = Mods.launching(this).delete() && Mods.anyApplied(this);
        Runnable next = modTrouble ? this::showModTrouble : this::showMain;
        File crash = new File(getExternalFilesDir(null), "crash.txt");
        if (crash.isFile()) showCrash(crash, next);
        else next.run();
    }

    /* The game crashed last time (host/window_android.c or GameActivity wrote crash.txt): offer to share a report,
       the crash and the end of the game's log, through any app the player likes. */
    private void showCrash(File crash, Runnable next) {
        mStop = true;
        Ui.clear(mRoot);
        Ui.heading(mRoot, "The game closed unexpectedly");
        text("Help get it fixed: share or copy the crash report and send it to Cleverdee any way you like, "
           + "on Reddit, GitHub or in a message. It has your phone model, Android version and the game's log; "
           + "nothing personal.", 15, Ui.SOFT);
        final String report = crashReport(crash);
        crash.delete();
        button("Share report", v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Cricket 07 crash report");
            send.putExtra(Intent.EXTRA_TEXT, report);
            try { startActivity(Intent.createChooser(send, "Send the crash report")); }
            catch (android.content.ActivityNotFoundException e) { /* nothing to send it with */ }
        });
        button("Copy report", v -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Cricket 07 crash report", report));
            android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
        });
        Ui.button(mRoot, "Continue", true, v -> next.run());
    }

    private String crashReport(File crash) {
        StringBuilder r = new StringBuilder("Cricket 07 crash report\n");
        String version = "?";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { /* unknown */ }
        r.append("App ").append(version).append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
         .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')');
        if (Build.VERSION.SDK_INT >= 31) r.append(", ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL);
        r.append("\n\n");
        for (String l : Mods.readLines(crash)) r.append(l).append('\n');
        java.util.List<String> log = Mods.readLines(new File(getExternalFilesDir(null), "last_log.txt"));
        r.append("\nlog (last lines):\n");
        for (int i = Math.max(0, log.size() - 120); i < log.size(); i++) r.append(log.get(i)).append('\n');
        return r.toString();
    }

    @Override
    protected void onDestroy() {
        stopNet();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mStop) { super.onBackPressed(); return; }
        stopNet();
        showMain();
    }

    /* ------------------------------------------------------------------ screens */

    private TextView text(String s, int size, int color) { return Ui.text(mRoot, s, size, color); }

    private View button(String s, View.OnClickListener l) { return Ui.button(mRoot, s, l); }

    private void showModTrouble() {
        mStop = true;
        Ui.clear(mRoot);
        Ui.heading(mRoot, "The game didn't start");
        text("Last time it closed before reaching the menu, with mods switched on. A mod may not suit this version "
           + "of the game.", 15, Ui.SOFT);
        button("Switch mods off", v -> {
            java.util.List<Mods.Mod> mods = Mods.list(this);
            for (Mods.Mod m : mods) m.on = false;
            try { Mods.save(this, mods); Mods.sync(this, true); } catch (IOException e) { /* shown on the Mods screen */ }
            showMain();
        });
        button("Keep them", v -> showMain());
    }

    private void showMain() {
        mStop = true;
        Ui.clear(mRoot);
        Ui.title(mRoot, "for Android");
        button("Play", v -> { startActivity(new Intent(this, GameActivity.class)); finish(); });
        button("Host a match", v -> showHost());
        button("Join a match", v -> showJoin());
        button("Mods", v -> { startActivity(new Intent(this, ModsActivity.class)); finish(); });
        if (!bundled())   /* add, replace or delete the player's own game files (SetupActivity) */
            Ui.button(mRoot, "Game files", true, v -> { startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_MANAGE, true)); finish(); });
        text("Multiplayer: both phones on the same Wi-Fi, or one on the other's hotspot", 12, Ui.DIM);
        Ui.button(mRoot, "Check for updates", true, v -> { startActivity(new Intent(this, UpdateActivity.class)); finish(); });
        if (sUpdate != null) showUpdateNote();
        else if (!sChecked && UpdateActivity.due(this)) {   /* quietly, at most once a day */
            sChecked = true;
            new Thread(() -> {
                try {
                    UpdateActivity.Release r = UpdateActivity.check(this);
                    if (r != null) runOnUiThread(() -> { sUpdate = r; if (mStop) showUpdateNote(); });
                } catch (java.io.IOException e) { /* offline: try another day */ }
            }, "update-check").start();
        }
    }

    private static UpdateActivity.Release sUpdate;   /* found by the quiet check */
    private static boolean sChecked;

    private void showUpdateNote() {
        TextView t = text("Version " + sUpdate.version + " is available: tap Check for updates", 14, Ui.GREEN);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
    }

    private boolean bundled() {
        try (InputStream in = getAssets().open("game_manifest.txt")) { return true; } catch (IOException e) { return false; }
    }

    private void showHost() {
        Ui.clear(mRoot);
        Ui.heading(mRoot, "Hosting");
        StringBuilder ips = new StringBuilder();
        for (InetAddress a : localAddresses()) ips.append(ips.length() > 0 ? "  or  " : "").append(a.getHostAddress());
        text(ips.length() > 0 ? "This phone: " + ips : "No network: turn on Wi-Fi or your hotspot", 16, Ui.SOFT);
        TextView status = text("Waiting for the other phone...", 16, Color.WHITE);
        button("Cancel", v -> { stopNet(); showMain(); });
        startNet(() -> hostLoop(status));
    }

    private void showJoin() {
        Ui.clear(mRoot);
        Ui.heading(mRoot, "Join a match");
        TextView status = text("Looking for matches on this network...", 16, Color.WHITE);
        LinearLayout found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        found.setGravity(Gravity.CENTER);
        Ui.side(mRoot).addView(found);
        EditText ip = new EditText(this);
        ip.setHint("Or type the host's address");
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        ip.setTextColor(Color.WHITE);
        ip.setHintTextColor(Ui.DIM);
        ip.setGravity(Gravity.CENTER);
        Ui.side(mRoot).addView(ip, new LinearLayout.LayoutParams((int) (300 * mDp), LinearLayout.LayoutParams.WRAP_CONTENT));
        final String[] target = { null };
        button("Join this address", v -> target[0] = ip.getText().toString().trim());
        button("Cancel", v -> { stopNet(); showMain(); });
        startNet(() -> joinLoop(status, found, target));
    }

    private void ui(TextView t, String s) { runOnUiThread(() -> { if (!mStop) t.setText(s); }); }

    /* ------------------------------------------------------------------ network */

    /* Sends this app's traffic (the lobby here, and the game's own socket later, host/netplay_udp.c) over Wi-Fi.
       When the Wi-Fi has no internet, which is usual on a phone's hotspot, Android sends it over mobile data
       instead, and the other phone is never reached. A phone sharing its own hotspot has no Wi-Fi network:
       hotspot clients are reached without binding. */
    private void useWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifi = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = n;
        }
        cm.bindProcessToNetwork(wifi);
    }

    private void startNet(Runnable r) {
        stopNet();
        mStop = false;
        useWifi();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mMulticast = wm.createMulticastLock("cricket07-lobby");   /* or some phones drop the broadcasts */
        mMulticast.acquire();
        new Thread(() -> {
            try { r.run(); } catch (RuntimeException e) { e.printStackTrace(); }
        }, "lobby").start();
    }

    private void stopNet() {
        mStop = true;
        DatagramSocket s = mSock;
        if (s != null) s.close();
        mSock = null;
        if (mMulticast != null && mMulticast.isHeld()) mMulticast.release();
    }

    private DatagramSocket open(int port) throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new java.net.InetSocketAddress(port));
        mSock = s;
        return s;
    }

    private static void send(DatagramSocket s, String msg, InetAddress to, int port) throws IOException {
        byte[] b = msg.getBytes("UTF-8");
        s.send(new DatagramPacket(b, b.length, to, port));
    }

    private static List<InetAddress> localAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                String name = n.getName();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")) continue;   /* mobile data */
                for (InterfaceAddress a : n.getInterfaceAddresses())
                    if (a.getAddress() instanceof java.net.Inet4Address) out.add(a.getAddress());
            }
        } catch (IOException e) { /* no network */ }
        return out;
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses())
                    if (a.getBroadcast() != null) out.add(a.getBroadcast());
            }
        } catch (IOException e) { /* no network */ }
        return out;
    }

    private void hostLoop(TextView status) {
        String build = buildId();
        try {
            DatagramSocket s = open(LOBBY_PORT);
            s.setSoTimeout(250);
            byte[] buf = new byte[512];
            InetAddress peer = null;
            while (!mStop && peer == null) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                String[] f = new String(p.getData(), 0, p.getLength(), "UTF-8").split("\\|");
                if (f[0].equals("CK07?")) send(s, "CK07!|" + Build.MODEL + "|" + build, p.getAddress(), p.getPort());
                else if (f[0].equals("CK07J") && f.length >= 3) {
                    if (f[2].equals(build)) peer = p.getAddress();
                    else {
                        send(s, "CK07X", p.getAddress(), p.getPort());
                        ui(status, f[1] + " has a different version: both phones need the same app, the same game files and the same mods");
                    }
                }
            }
            if (peer == null) return;
            ui(status, "Connected. Checking the connection...");
            /* round trips: the input delay has to cover them */
            s.setSoTimeout(100);
            List<Long> trips = new ArrayList<>();
            for (int i = 0; i < 25 && !mStop; i++) {
                long t0 = System.nanoTime();
                send(s, "CK07P|" + i, peer, LOBBY_PORT);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    if (new String(p.getData(), 0, p.getLength(), "UTF-8").equals("CK07Q|" + i))
                        trips.add((System.nanoTime() - t0) / 1000000);
                } catch (SocketTimeoutException e) { /* lost */ }
            }
            long rtt = 100;
            if (!trips.isEmpty()) { Collections.sort(trips); rtt = trips.get(Math.min(trips.size() - 1, trips.size() * 9 / 10)); }
            int delay = Math.max(2, Math.min(12, (int) Math.ceil((rtt / 2.0) / 16.67) + 2));
            int session = new Random().nextInt(1 << 30) + 1;
            ui(status, "Starting (" + rtt + " ms, delay " + delay + " frames)...");
            s.setSoTimeout(100);
            boolean acked = false;
            for (int i = 0; i < 50 && !mStop && !acked; i++) {
                send(s, "CK07S|" + session + "|" + delay, peer, LOBBY_PORT);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); acked = new String(p.getData(), 0, p.getLength(), "UTF-8").equals("CK07A"); }
                catch (SocketTimeoutException e) { /* again */ }
            }
            if (!acked) { ui(status, "The other phone stopped answering. Try again."); return; }
            launch(0, peer, session, delay);
        } catch (IOException e) {
            if (!mStop) ui(status, "Network problem: " + e.getMessage());
        }
    }

    private void joinLoop(TextView status, LinearLayout found, String[] target) {
        String build = buildId();
        Map<String, InetAddress> hosts = new LinkedHashMap<>();
        try {
            DatagramSocket s = open(LOBBY_PORT);
            s.setSoTimeout(200);
            byte[] buf = new byte[512];
            InetAddress host = null;
            long lastAsk = 0, lastJoin = 0, started = System.currentTimeMillis(), joinedAt = 0;
            boolean hinted = false;
            for (;;) {
                if (mStop) return;
                long now = System.currentTimeMillis();
                if (!hinted && hosts.isEmpty() && host == null && now - started > 8000) {
                    hinted = true;
                    ui(status, "No match found yet. Is the other phone on Host a match, on the same Wi-Fi? Some routers keep "
                             + "phones apart: then turn on one phone's hotspot, join it with the other, and try again. "
                             + "You can also type the address the host shows.");
                }
                if (host != null && joinedAt > 0 && now - joinedAt > 6000) {
                    joinedAt = 0;
                    ui(status, "No answer from that phone. Check the address, that it's on Host a match, and that both "
                             + "phones are on the same network.");
                }
                if (target[0] != null && !target[0].isEmpty()) {
                    try { host = InetAddress.getByName(target[0]); }
                    catch (IOException e) { ui(status, "That address doesn't look right"); host = null; }
                    target[0] = null;
                    lastJoin = 0;
                    joinedAt = System.currentTimeMillis();
                }
                if (host == null && now - lastAsk > 1000) {
                    lastAsk = now;
                    for (InetAddress b : broadcastAddresses()) {
                        try { send(s, "CK07?", b, LOBBY_PORT); } catch (IOException e) { /* that network's gone */ }
                    }
                }
                if (host != null && now - lastJoin > 500) {
                    lastJoin = now;
                    send(s, "CK07J|" + Build.MODEL + "|" + build, host, LOBBY_PORT);
                    if (joinedAt > 0) ui(status, "Connecting...");
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                String[] f = new String(p.getData(), 0, p.getLength(), "UTF-8").split("\\|");
                if (f[0].equals("CK07!") && f.length >= 3 && host == null) {
                    final String name = f[1];
                    final InetAddress from = p.getAddress();
                    final boolean same = f[2].equals(build);
                    if (!hosts.containsKey(name)) {
                        hosts.put(name, from);
                        runOnUiThread(() -> {
                            Ui.MenuButton b = Ui.button(found, same ? "Join " + name : name + " (different version)",
                                                        v -> target[0] = from.getHostAddress());
                            b.setEnabled(same);
                        });
                        ui(status, "Found:");
                    }
                } else if (f[0].equals("CK07X")) {
                    host = null;
                    ui(status, "Different version: both phones need the same app, the same game files and the same mods");
                } else if (f[0].equals("CK07P") && f.length >= 2) {
                    joinedAt = 0;
                    send(s, "CK07Q|" + f[1], p.getAddress(), p.getPort());
                    ui(status, "Connected. Checking the connection...");
                } else if (f[0].equals("CK07S") && f.length >= 3) {
                    for (int i = 0; i < 5; i++) send(s, "CK07A", p.getAddress(), p.getPort());
                    launch(1, p.getAddress(), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                    return;
                }
            }
        } catch (IOException e) {
            if (!mStop) ui(status, "Network problem: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ starting */

    /* The game code and the game's data: lockstep needs identical copies. The app's library, cricket07.exe and every
       file in Data/ (path and size: a roster or kit mod changes them) count; the rest of the game folder doesn't. */
    private String buildId() {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[1 << 16];
        File game = new File(getExternalFilesDir(null), "game");
        for (File f : new File[] { new File(getApplicationInfo().nativeLibraryDir, "libcricket07.so"), new File(game, "cricket07.exe") }) {
            try (InputStream in = new FileInputStream(f)) {
                for (int n; (n = in.read(buf)) > 0; ) crc.update(buf, 0, n);
            } catch (IOException e) { crc.update(0); }
        }
        File data = null;
        File[] kids = game.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory() && k.getName().equalsIgnoreCase("Data")) data = k;
        List<String> list = new ArrayList<>();
        if (data != null) listFiles(data, "", list);
        Collections.sort(list);
        for (String s : list) crc.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    private static void listFiles(File dir, String rel, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String p = rel + k.getName().toLowerCase(java.util.Locale.US);
            if (k.isDirectory()) listFiles(k, p + "/", out);
            else out.add(p + "|" + k.length() + ";");
        }
    }

    private void launch(int slot, InetAddress peer, int session, int delay) {
        stopNet();
        File ext = getExternalFilesDir(null);
        File user = new File(ext, "netplay_user");   /* fresh saves, alike on both phones; the player's own stay untouched */
        deleteTree(user);
        user.mkdirs();
        copy(new File(ext, "userdata/gpu_pipelines.bin"), new File(user, "gpu_pipelines.bin"));   /* only graphics: fewer hitches */
        try {
            Os.setenv("RC_DETERMINISTIC", "1", true);
            Os.setenv("RC_NETPLAY", "1", true);
            Os.setenv("RC_NP_PLAYERS", "2", true);
            Os.setenv("RC_PADS", "2", true);     /* the game only sees the pads it is told about at startup */
            Os.setenv("RC_NP_SLOT", String.valueOf(slot), true);
            Os.setenv("RC_NP_DELAY", String.valueOf(delay), true);
            Os.setenv("RC_NP_SESSION", String.valueOf(session), true);
            Os.setenv("RC_NP_PEER", peer.getHostAddress() + ":" + GAME_PORT, true);
            Os.setenv("RC_NP_PORT", String.valueOf(GAME_PORT), true);
            Os.setenv("RC_USERDATA", user.getPath(), true);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
        runOnUiThread(() -> { startActivity(new Intent(this, GameActivity.class)); finish(); });
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static void copy(File from, File to) {
        if (!from.isFile()) return;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (IOException e) { /* only a cache */ }
    }
}
