package io.github.maparham.autotether;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.muntashirakon.adb.AdbStream;

/**
 * Connects to the phone's own adb and runs the tether helper as shell.
 *
 * Two doors, in preference order:
 *  1. Loopback TCP (127.0.0.1:5555) — works with Wi-Fi OFF. Available once adbd has been put into
 *     "tcpip" mode (we do that ourselves, see {@link #armTcpip}). Resets on reboot.
 *  2. Wireless Debugging (mDNS/TLS) — needs Wi-Fi connected. Used for the first connect each boot,
 *     and it's where we (re)arm door #1.
 */
public class AdbRunner {
    static final String DEX = "/data/local/tmp/tether.dex";
    static final String TETHER_CMD = "shell:CLASSPATH=" + DEX + " app_process /system/bin Main 5 start";
    static final String USB_CMD = "shell:CLASSPATH=" + DEX + " app_process /system/bin Main 1 start";
    static final int LOCAL_PORT = 5555;

    // Whether adbd is currently in tcpip mode this boot. Self-healing: a successful loopback connect
    // sets it; a reboot clears tcpip and (with the process restart) this flag.
    static volatile boolean tcpipArmed = false;

    // Whether the helper dex has been confirmed present this process-life. The existence check is a
    // shell round-trip that can stall on a freshly (re)opened loopback stream; once we've confirmed
    // (or pushed) the dex, skip it so the time-critical tether call isn't delayed. Cleared on reboot
    // (the dex lives in /data/local/tmp) along with the process and these statics.
    static volatile boolean dexReady = false;

    /**
     * Get a connected manager, preferring the Wi-Fi-free loopback door and falling back to
     * Wireless Debugging. Throws AdbPairingRequiredException if never paired.
     */
    static synchronized AdbManager connect(Context ctx, long timeoutMs) throws Exception {
        AdbManager m = AdbManager.getInstance(ctx);
        if (m.isConnected()) return m;
        // 1) Loopback first — no Wi-Fi needed. Fast connection-refused if tcpip isn't up yet.
        try {
            if (m.connect("127.0.0.1", LOCAL_PORT)) {
                tcpipArmed = true;
                Log.i("AutoTether", "connected via loopback 127.0.0.1:" + LOCAL_PORT + " (no Wi-Fi needed)");
                return m;
            }
        } catch (Throwable ignore) { /* port not listening (e.g. after reboot) — fall through to Wi-Fi */ }
        // 2) Wireless Debugging over Wi-Fi.
        m.autoConnect(ctx, timeoutMs); // throws AdbPairingRequiredException if unpaired
        Log.i("AutoTether", "connected via Wireless Debugging");
        return m;
    }

    /**
     * Ask adbd to listen on TCP {@link #LOCAL_PORT} (all interfaces incl. loopback), so future
     * connects work with Wi-Fi off. This restarts adbd, dropping the current connection — callers
     * run it only while idle, never mid-command. No-op if already armed.
     */
    static void armTcpip(AdbManager m) {
        if (tcpipArmed) return;
        try {
            // Opening this service is exactly what host-side `adb tcpip 5555` does.
            m.openStream("tcpip:" + LOCAL_PORT).close();
            tcpipArmed = true;
            Log.i("AutoTether", "enabled adb tcpip " + LOCAL_PORT + " — Wi-Fi can now be turned off");
        } catch (Throwable t) {
            Log.w("AutoTether", "arm tcpip failed: " + t);
        }
    }

    /**
     * Keep the adb connection alive proactively, and once up over Wi-Fi, arm the loopback door so
     * the next connect needs no Wi-Fi. Run from the watcher loop while idle. Swallows failures.
     */
    public static synchronized void maintainConnection(Context ctx) {
        try {
            AdbManager m = AdbManager.getInstance(ctx);
            if (!m.isConnected()) connect(ctx, 12000);
            // If we're up via Wi-Fi (loopback connect would have set tcpipArmed), switch adbd to
            // tcpip now while idle. This drops the connection; next loop reconnects via loopback.
            if (!tcpipArmed && m.isConnected()) armTcpip(m);
        } catch (Throwable ignore) {}
    }

    /** Ensure connected and the helper dex is in place. */
    public static synchronized void ensureReady(Context ctx) throws Exception {
        AdbManager m = connect(ctx, 20000); // loopback (no Wi-Fi) → Wireless Debugging fallback
        if (dexReady) return;
        if (!runCmd(m, "shell:[ -f " + DEX + " ] && echo yes || echo no").contains("yes")) {
            pushHelper(ctx, m);
        }
        dexReady = true;
    }

    public static String tether(Context ctx) throws Exception {
        ensureReady(ctx);
        String r = runCmd(AdbManager.getInstance(ctx), TETHER_CMD);
        Log.i("AutoTether", "tether result: " + r);
        return r;
    }

    public static String usbTether(Context ctx) throws Exception {
        ensureReady(ctx);
        String r = runCmd(AdbManager.getInstance(ctx), USB_CMD);
        Log.i("AutoTether", "usb tether result: " + r);
        return r;
    }

    static String runCmd(AdbManager m, String cmd) throws Exception {
        return runCmd(m, cmd, 15000);
    }

    /**
     * Run a shell command, but never block longer than {@code timeoutMs}. A dead/half-open self-adb
     * stream can otherwise leave {@code in.read()} blocked forever — and since the watcher polls on a
     * single thread, that one hang freezes adapter detection entirely (the "stuck at enabling…" bug).
     * A watchdog force-closes the stream on overrun, which unblocks the read so we return promptly.
     */
    static String runCmd(AdbManager m, String cmd, long timeoutMs) throws Exception {
        final AdbStream s = m.openStream(cmd);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(timeoutMs); } catch (InterruptedException e) { return; }
            Log.w("AutoTether", "runCmd timed out after " + timeoutMs + "ms, closing stream: " + cmd);
            try { s.close(); } catch (Throwable ignore) {}
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            InputStream in = s.openInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            try { while ((n = in.read(buf)) > 0) bos.write(buf, 0, n); } catch (Exception ignore) {}
            return new String(bos.toByteArray(), "UTF-8").trim();
        } finally {
            watchdog.interrupt();
            try { s.close(); } catch (Throwable ignore) {}
        }
    }

    static void pushHelper(Context ctx, AdbManager m) throws Exception {
        byte[] dex;
        try (InputStream is = ctx.getAssets().open("tether.dex")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            dex = bos.toByteArray();
        }
        String b64 = Base64.encodeToString(dex, Base64.NO_WRAP);
        try (AdbStream s = m.openStream("shell:base64 -d > " + DEX)) {
            OutputStream out = s.openOutputStream();
            out.write(b64.getBytes("UTF-8"));
            out.flush();
        }
        Log.i("AutoTether", "pushed helper dex (" + dex.length + " bytes)");
    }
}
