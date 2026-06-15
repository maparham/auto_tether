package io.github.maparham.autotether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/** Small status notification shown during auto-pairing. */
public class PairReceiver {
    static final String CHANNEL = "pairing";
    static final int NID = 42;

    static void show(Context ctx, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Auto Tether pairing", NotificationManager.IMPORTANCE_HIGH));
        }
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setContentTitle("Auto Tether — pairing")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
        nm.notify(NID, n);
    }

    static void cancel(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(NID);
    }
}
