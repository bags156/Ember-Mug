package io.github.bags156.embermug;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import io.github.bags156.embermug.core.MugState;
import io.github.bags156.embermug.core.TempUnit;
import io.github.bags156.embermug.core.Temperatures;

/** Notification channels, the ongoing connection notification and alerts. */
final class Notifications {

    private Notifications() {
    }

    static final String CHANNEL_STATUS = "connection";
    static final String CHANNEL_ALERTS = "alerts";

    static final int ID_SERVICE = 1;
    static final int ID_LIQUID = 2;
    static final int ID_CONNECTION = 3;
    static final int ID_TARGET = 4;

    private static final int ACCENT = 0xFFE8590C;

    static void ensureChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CHANNEL_STATUS, "Mug connection",
                NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Shown while the app stays connected to your mug in the background.");
        status.setShowBadge(false);
        NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS, "Mug alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Empty mug, refill, target reached, disconnects and heater-off results.");
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(alerts);
    }

    static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static Notification service(Context context, MugState state, TempUnit unit) {
        String title = (state.name != null ? state.name : "Ember Mug") + " · " + state.link.label;
        StringBuilder text = new StringBuilder();
        if (state.currentCenti != null) {
            text.append("Drink ").append(Temperatures.format(state.currentCenti, unit));
        }
        if (state.targetCenti != null) {
            appendDot(text).append(state.targetCenti == 0
                    ? "Heater off"
                    : "Target " + Temperatures.format(state.targetCenti, unit));
        }
        if (state.batteryPercent != null) {
            appendDot(text).append("Battery ").append(state.batteryPercent).append('%');
        }
        if (text.length() == 0) {
            text.append("Keeping your mug connected in the background.");
        }
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mug)
                .setColor(ACCENT)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openApp(context));
        if (state.isReady() && state.heaterMaybeOn()) {
            builder.addAction(action(context, "Heater off", EmberService.ACTION_HEATER_OFF, 11));
        }
        builder.addAction(action(context, "Disconnect", EmberService.ACTION_DISCONNECT, 12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    /** Posts an alert. {@code action} may be null. */
    static void alert(Context context, int id, String title, String text, Notification.Action action) {
        if (!canPost(context)) {
            return;
        }
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_mug)
                .setColor(ACCENT)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openApp(context));
        if (action != null) {
            builder.addAction(action);
        }
        context.getSystemService(NotificationManager.class).notify(id, builder.build());
    }

    static void cancel(Context context, int id) {
        context.getSystemService(NotificationManager.class).cancel(id);
    }

    static Notification.Action heatAction(Context context, String label) {
        return action(context, label, EmberService.ACTION_HEAT_SELECTED, 13);
    }

    private static Notification.Action action(Context context, String label, String serviceAction,
            int requestCode) {
        Intent intent = new Intent(context, EmberService.class).setAction(serviceAction);
        PendingIntent pending = PendingIntent.getService(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stat_mug), label, pending).build();
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static StringBuilder appendDot(StringBuilder text) {
        if (text.length() > 0) {
            text.append(" · ");
        }
        return text;
    }
}
