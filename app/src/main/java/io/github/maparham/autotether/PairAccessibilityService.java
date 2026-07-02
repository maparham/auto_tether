package io.github.maparham.autotether;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
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

    /** When set (a future timestamp), auto-tap "Wireless debugging" in Developer options. */
    public static volatile long navigateWdUntil = 0;
    private int scrolls = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (busy) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // Requested navigation: walk into Wireless debugging and open the pairing dialog.
        if (System.currentTimeMillis() < navigateWdUntil) {
            if (navigateAndPair(root)) { navigateWdUntil = 0; scrolls = 0; }
        }

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
                boolean ok = AdbRunner.pair(getApplicationContext(), host, port, code);
                if (!ok) { PairReceiver.show(getApplicationContext(), "Pairing failed — reopen the pair dialog."); return; }
                PairReceiver.show(getApplicationContext(), "Paired ✓ connecting…");
                AdbRunner.ensureReady(getApplicationContext());
                getApplicationContext().getSharedPreferences("autotether", MODE_PRIVATE)
                        .edit().putBoolean("paired", true).apply();
                PairReceiver.show(getApplicationContext(), "Connected ✓ — done. Tethering will turn on by itself.");
                Intent s = new Intent(getApplicationContext(), TetherService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
                // bring Auto Tether to the front (user is in the Wireless-debugging screen)
                try {
                    startActivity(new Intent(getApplicationContext(), MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (Throwable ignore) {}
                try { Thread.sleep(4000); } catch (InterruptedException ignore) {}
                PairReceiver.cancel(getApplicationContext()); // pairing done — clear the notice
            } catch (Throwable t) {
                PairReceiver.show(getApplicationContext(), "Error: " + t);
                Log.e("AutoTether", "auto-pair failed", t);
            } finally {
                try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
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

    /**
     * Drive the UI toward pairing: open the pairing dialog if we can see it; otherwise step into
     * "Wireless debugging"; otherwise scroll to find one. Returns true when the final target is tapped.
     */
    private boolean navigateAndPair(AccessibilityNodeInfo root) {
        if (clickByText(root, "Pair device with pairing code")) return true; // dialog opens → pairing proceeds
        if (clickByText(root, "Wireless debugging")) { scrolls = 0; return false; } // step into the WD screen
        if (scrolls++ < 15) {
            AccessibilityNodeInfo sc = findScrollable(root);
            if (sc != null) sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        } else { navigateWdUntil = 0; scrolls = 0; }
        return false;
    }

    private boolean clickByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo n : nodes) {
            if (n.getText() == null || !n.getText().toString().equalsIgnoreCase(text)) continue;
            AccessibilityNodeInfo c = n;
            for (int i = 0; i < 6 && c != null && !c.isClickable(); i++) c = c.getParent();
            if (c != null && c.isClickable() && c.isEnabled()) {
                c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    @Override public void onInterrupt() {}
}
