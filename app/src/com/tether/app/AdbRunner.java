package com.tether.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.File;
import java.net.Socket;

/** Connects to the phone's own adb (127.0.0.1:5555) and runs a shell command as shell-uid. */
public class AdbRunner {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 5555;
    public static final String TETHER_CMD =
        "shell:CLASSPATH=/data/local/tmp/tether.dex app_process /system/bin Main 5 start";
    public static final String USB_CMD =
        "shell:CLASSPATH=/data/local/tmp/tether.dex app_process /system/bin Main 1 start";

    public static synchronized String run(Context ctx, String cmd) throws Exception {
        AdbBase64 base64 = data -> Base64.encodeToString(data, Base64.NO_WRAP);
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        AdbCrypto crypto;
        if (priv.exists() && pub.exists()) {
            crypto = AdbCrypto.loadAdbKeyPair(base64, priv, pub);
        } else {
            crypto = AdbCrypto.generateAdbKeyPair(base64);
            crypto.saveAdbKeyPair(priv, pub);
        }
        Socket socket = new Socket(HOST, PORT);
        AdbConnection conn = AdbConnection.create(socket, crypto);
        conn.connect();
        AdbStream stream = conn.open(cmd);
        StringBuilder out = new StringBuilder();
        while (!stream.isClosed()) {
            try { out.append(new String(stream.read())); }
            catch (Exception e) { break; }
        }
        conn.close();
        String result = out.toString().trim();
        Log.i("AutoTether", "adb cmd result: " + result);
        return result;
    }

    public static String tether(Context ctx) throws Exception {
        return run(ctx, TETHER_CMD);
    }

    public static String usbTether(Context ctx) throws Exception {
        return run(ctx, USB_CMD);
    }
}
