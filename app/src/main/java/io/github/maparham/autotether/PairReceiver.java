package io.github.maparham.autotether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/** Receives the pairing port+code typed into the notification's reply field and pairs. */
public class PairReceiver extends BroadcastReceiver {
    static final String KEY = "pair_input";
    static final String CHANNEL = "pairing";
    static final int NID = 42;

    /** Show (or update) the reply notification. */
    static void show(Context ctx, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Auto Tether pairing", NotificationManager.IMPORTANCE_HIGH));
        }
        RemoteInput ri = new RemoteInput.Builder(KEY)
                .setLabel("port code  (e.g. 43797 358014)").build();
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, PairReceiver.class),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_menu_send),
                "Enter port + code", pi).addRemoteInput(ri).build();
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setContentTitle("Auto Tether — pair")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .addAction(action)
                .setOngoing(true)
                .build();
        nm.notify(NID, n);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence cs = results.getCharSequence(KEY);
        if (cs == null) return;
        final String text = cs.toString().trim();
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String[] parts = text.split("[\\s,:]+");
                int port = Integer.parseInt(parts[0]);
                String code = parts[parts.length - 1];
                show(app, "Pairing…");
                String host = MainActivity.wifiIp();
                if (host == null) host = "127.0.0.1";
                Log.i("AutoTether", "pairing to " + host + ":" + port);
                boolean ok = AdbManager.getInstance(app).pair(host, port, code);
                if (!ok) { show(app, "Pairing failed — open a fresh code and reply again."); return; }
                show(app, "Paired ✓ connecting…");
                AdbRunner.ensureReady(app);
                show(app, "Connected ✓ — done. Tethering will turn on by itself.");
                Intent s = new Intent(app, TetherService.class);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(s); else app.startService(s);
            } catch (Throwable t) {
                show(app, "Error: " + t);
                Log.e("AutoTether", "pair failed", t);
            } finally {
                pr.finish();
            }
        }).start();
    }
}
