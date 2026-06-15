package io.github.maparham.autotether;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the "Pair device with pairing code" dialog (code + IP:port) and pairs automatically,
 * so the user never has to type or switch apps. Acts only on that dialog.
 */
public class PairAccessibilityService extends AccessibilityService {
    private static final Pattern IP_PORT =
            Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{2,5})");
    private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{3}\\s?\\d{3})(?!\\d)");

    private volatile boolean busy = false;
    private String lastKey = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (busy) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder sb = new StringBuilder();
        collect(root, sb);
        String all = sb.toString();
        if (!all.toLowerCase().contains("pairing code")) return; // only the pairing dialog

        Matcher ipm = IP_PORT.matcher(all);
        if (!ipm.find()) return;
        final String host = ipm.group(1);
        final int port = Integer.parseInt(ipm.group(2));

        String rest = all.substring(0, ipm.start()) + " " + all.substring(ipm.end());
        Matcher cm = CODE.matcher(rest);
        if (!cm.find()) return;
        final String code = cm.group(1).replaceAll("\\s", "");

        String key = host + ":" + port + ":" + code;
        if (key.equals(lastKey)) return; // same dialog, already handling
        lastKey = key;
        busy = true;

        PairReceiver.show(getApplicationContext(), "Pairing code detected — pairing…");
        new Thread(() -> {
            try {
                Log.i("AutoTether", "auto-pair to " + host + ":" + port);
                boolean ok = AdbManager.getInstance(getApplicationContext()).pair(host, port, code);
                if (!ok) { PairReceiver.show(getApplicationContext(), "Pairing failed — reopen the pair dialog."); return; }
                PairReceiver.show(getApplicationContext(), "Paired ✓ connecting…");
                AdbRunner.ensureReady(getApplicationContext());
                PairReceiver.show(getApplicationContext(), "Connected ✓ — done. Tethering will turn on by itself.");
                Intent s = new Intent(getApplicationContext(), TetherService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
            } catch (Throwable t) {
                PairReceiver.show(getApplicationContext(), "Error: " + t);
                Log.e("AutoTether", "auto-pair failed", t);
            } finally {
                try { Thread.sleep(4000); } catch (InterruptedException ignore) {}
                lastKey = "";
                busy = false;
            }
        }).start();
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        if (node.getText() != null) sb.append(node.getText()).append('\n');
        if (node.getContentDescription() != null) sb.append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), sb);
    }

    @Override public void onInterrupt() {}
}
