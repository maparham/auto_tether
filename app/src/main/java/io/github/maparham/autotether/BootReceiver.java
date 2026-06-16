package io.github.maparham.autotether;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * (Re)starts the watcher service. Fires on boot, on plugging into a computer (power connected),
 * and on attaching the USB-Ethernet adapter — so even if the watcher was killed during a long idle,
 * the act of plugging in revives it and tethering still turns on.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i("AutoTether", "wake trigger: " + (intent != null ? intent.getAction() : "null") + " → starting watcher");
        try {
            Intent i = new Intent(ctx, TetherService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Throwable t) {
            // Starting a foreground service from the background can be blocked unless we're
            // battery-optimization-exempt; the app requests that exemption on launch.
            Log.w("AutoTether", "could not start watcher from receiver: " + t);
        }
    }
}
