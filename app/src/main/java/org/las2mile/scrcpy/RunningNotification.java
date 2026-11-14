package org.las2mile.scrcpy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * "scrcpy is running" notification with a Stop button for sessions shown full screen by
 * {@link MainActivity}. (The floating window has its own foreground-service notification with
 * the same title and a Stop button.) Stop is delivered to {@link StopReceiver}.
 */
final class RunningNotification {
    private static final String TAG = "RunningNotification";
    private static final String CHANNEL_ID = "scrcpy_running";
    private static final int NOTIFICATION_ID = 2;

    /** Set by MainActivity while it is alive: ends its session. Main thread only. */
    private static Runnable stopHandler;

    private RunningNotification() {
    }

    static void setStopHandler(Runnable handler) {
        stopHandler = handler;
    }

    static void clearStopHandler(Runnable handler) {
        if (stopHandler == handler) {
            stopHandler = null;
        }
    }

    static Runnable stopHandler() {
        return stopHandler;
    }

    static void show(Context context, String text) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, build(context, manager, text));
            }
        } catch (RuntimeException e) {
            // E.g. notification permission denied on Android 13+: the session works without it.
            Log.w(TAG, "Could not show the notification", e);
        }
    }

    static void cancel(Context context) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not remove the notification", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static Notification build(Context context, NotificationManager manager, String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.running_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_LOW);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent stop = new Intent(context, StopReceiver.class).setAction(StopReceiver.ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, 0, stop, flags);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(context, 1, open, flags);
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.running_title))
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.notification_stop), stopIntent).build())
                .build();
    }
}
