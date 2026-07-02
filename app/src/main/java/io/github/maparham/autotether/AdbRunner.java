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
    static final String USB_STOP_CMD = "shell:CLASSPATH=" + DEX + " app_process /system/bin Main 1 stop";
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
    // SECURITY NOTE: adbd's `tcpip:` service always binds 0.0.0.0 — there is no loopback-only mode
    // without root. Enabling the Wi-Fi-free loopback door therefore also exposes adb on 5555 to every
    // interface (incl. the local network). adb's key-based auth still gates new hosts, but any
    // already-authorized key reaches a full shell; avoid arming this on untrusted networks.
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
        byte[] dex = readAsset(ctx);
        // Confirm the dex is present AND complete before latching dexReady — a `[ -f ]` existence
        // check passes for a half-written file, and `app_process` would then load a truncated dex
        // and fail forever (dexReady stays true, so we'd never re-push). Compare byte size instead.
        if (remoteSize(m) != dex.length) {
            pushHelper(m, dex);
            int after = remoteSize(m);
            // Throw only on a *definite* wrong size (a truncated push). remoteSize returns -1 when it
            // couldn't read the size at all — a transient stream stall, not a short file (`wc -c` on any
            // existing file yields its byte count). Treating -1 as failure here would spuriously report
            // "incomplete" and re-push the whole dex every poll on a slow loopback stream, and never
            // latch dexReady. pushHelper already throws if the stream write itself failed, so trust it.
            if (after >= 0 && after != dex.length)
                throw new Exception("helper dex push incomplete (remote size " + after + " != " + dex.length + ")");
        }
        dexReady = true;
    }

    /** Size of the on-device dex in bytes, or -1 if absent/unreadable. */
    static int remoteSize(AdbManager m) throws Exception {
        String out = runCmd(m, "shell:wc -c < " + DEX + " 2>/dev/null").trim();
        try { return Integer.parseInt(out); } catch (NumberFormatException e) { return -1; }
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

    /** Clear a wedged USB-tether request (startTethering returning DUPLICATE_REQUEST with nothing
     *  actually tethering) by stopping then starting again, so the framework re-issues it fresh. */
    public static String usbTetherReset(Context ctx) throws Exception {
        ensureReady(ctx);
        AdbManager m = AdbManager.getInstance(ctx);
        runCmd(m, USB_STOP_CMD);
        try { Thread.sleep(800); } catch (InterruptedException ignore) {}
        String r = runCmd(m, USB_CMD);
        Log.i("AutoTether", "usb tether reset (stop→start) result: " + r);
        return r;
    }

    /**
     * Pair to a Wireless-debugging endpoint, serialized against every other connection user via this
     * class's lock. The accessibility service pairs on its own thread while the watcher loop runs
     * {@link #maintainConnection} concurrently; both touch the one AdbManager singleton, and pairing
     * outside this lock can corrupt the shared connection state.
     */
    public static synchronized boolean pair(Context ctx, String host, int port, String code) throws Exception {
        return AdbManager.getInstance(ctx).pair(host, port, code);
    }

    static String runCmd(AdbManager m, String cmd) throws Exception {
        return runCmd(m, cmd, 15000);
    }

    /**
     * Run a shell command, but never block the calling thread longer than {@code timeoutMs}. The work
     * (openStream + read) runs on a daemon worker; the caller only join()s with a timeout. This is
     * crucial because the watcher polls on a single thread: a dead/half-open self-adb stream can hang
     * in {@code in.read()} forever, which would freeze adapter detection entirely (the "stuck at
     * enabling…" bug).
     *
     * On overrun we tear down the whole connection with {@code disconnect()} (not just this stream), so a
     * timed-out command can't leave a half-dead connection for the next command to reuse: disconnect
     * closes the socket — which unblocks a stalled read so the abandoned worker dies — and the next
     * command reconnects fresh. adbd stays in tcpip mode (tcpipArmed unchanged), so that reconnect is a
     * cheap loopback dial. We use disconnect(), NOT close(): close() also destroys the private key
     * (terminal — would break the next TLS reconnect/pair), whereas disconnect() just drops the socket
     * and leaves the manager reusable. The library multiplexes streams by id, so a lingering worker
     * doesn't corrupt framing; the only real hazard is a stale connection, which the reconnect clears.
     *
     * Known limitation: if the hang is inside {@code openStream}/{@code AdbConnection.open} (adbd accepts
     * the TCP connect but never ACKs the OPEN), the worker holds the library's connection lock, and
     * disconnect() — like isConnected() and the next openStream — blocks on it until open() hits its own
     * socket error. Nothing at this layer can pre-empt that; it's an adbd/library edge. The common case
     * (a mid-stream read stall) does not hold that lock and recovers cleanly.
     */
    static String runCmd(AdbManager m, String cmd, long timeoutMs) throws Exception {
        final AdbStream[] holder = new AdbStream[1];
        final String[] result = new String[1];
        final Throwable[] err = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                AdbStream s = m.openStream(cmd);
                holder[0] = s;
                InputStream in = s.openInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                try { while ((n = in.read(buf)) > 0) bos.write(buf, 0, n); } catch (Exception ignore) {}
                result[0] = new String(bos.toByteArray(), "UTF-8").trim();
            } catch (Throwable t) { err[0] = t; }
            finally { try { if (holder[0] != null) holder[0].close(); } catch (Throwable ignore) {} }
        });
        worker.setDaemon(true);
        worker.start();
        worker.join(timeoutMs);
        if (worker.isAlive()) {
            Log.w("AutoTether", "runCmd timed out after " + timeoutMs + "ms, resetting connection: " + cmd);
            try { m.disconnect(); } catch (Throwable ignore) {} // unblocks the worker and forces a fresh reconnect
            worker.interrupt();
            throw new Exception("runCmd timeout after " + timeoutMs + "ms: " + cmd);
        }
        if (err[0] != null) throw new Exception("runCmd failed: " + cmd, err[0]);
        return result[0] != null ? result[0] : "";
    }

    /** Read the bundled helper dex from app assets into memory. */
    static byte[] readAsset(Context ctx) throws Exception {
        try (InputStream is = ctx.getAssets().open("tether.dex")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static void pushHelper(AdbManager m, byte[] dex) throws Exception {
        String b64 = Base64.encodeToString(dex, Base64.NO_WRAP);
        try (AdbStream s = m.openStream("shell:base64 -d > " + DEX)) {
            OutputStream out = s.openOutputStream();
            out.write(b64.getBytes("UTF-8"));
            out.flush();
        }
        Log.i("AutoTether", "pushed helper dex (" + dex.length + " bytes)");
    }
}
