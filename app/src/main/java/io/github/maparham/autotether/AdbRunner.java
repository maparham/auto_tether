package io.github.maparham.autotether;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.muntashirakon.adb.AdbStream;

/** Connects to the phone's adb over Wireless Debugging (TLS) and runs the tether helper as shell. */
public class AdbRunner {
    static final String DEX = "/data/local/tmp/tether.dex";
    static final String TETHER_CMD = "shell:CLASSPATH=" + DEX + " app_process /system/bin Main 5 start";
    static final String USB_CMD = "shell:CLASSPATH=" + DEX + " app_process /system/bin Main 1 start";

    /** Ensure connected (auto-discovered over mDNS) and the helper dex is in place. */
    public static synchronized void ensureReady(Context ctx) throws Exception {
        AdbManager m = AdbManager.getInstance(ctx);
        if (!m.isConnected()) m.autoConnect(ctx, 20000); // throws AdbPairingRequiredException if unpaired
        if (!runCmd(m, "shell:[ -f " + DEX + " ] && echo yes || echo no").contains("yes")) {
            pushHelper(ctx, m);
        }
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
        try (AdbStream s = m.openStream(cmd)) {
            InputStream in = s.openInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            try { while ((n = in.read(buf)) > 0) bos.write(buf, 0, n); } catch (Exception ignore) {}
            return new String(bos.toByteArray(), "UTF-8").trim();
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
