package com.lemonrsh.phoneshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Date;
import java.util.List;

/**
 * "&lt;name&gt; is connected to this phone" notification on the phone that is being mirrored,
 * with a Disconnect button. It stays (ongoing) for as long as a session runs: posted by
 * {@link IncomingSessionService} as its foreground notification, or directly by
 * {@link IncomingSessionReceiver} when Android does not let the service start.
 */
final class IncomingNotifier {
    private static final String TAG = "IncomingNotifier";
    static final String CHANNEL_ID = "incoming_session";
    static final int NOTIFICATION_ID = 7;

    private IncomingNotifier() {
    }

    static void show(Context context, List<IncomingSessions.Session> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            cancel(context);
            return;
        }
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, build(context, sessions));
            }
        } catch (RuntimeException e) {
            // E.g. notification permission denied on Android 13+.
            Log.w(TAG, "Could not show the connected notification", e);
        }
    }

    static void cancel(Context context) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not remove the connected notification", e);
        }
    }

    static String displayName(Context context, IncomingSessions.Session session) {
        String name = session.clientName;
        return name.isEmpty() || "-".equals(name) ? context.getString(R.string.incoming_unknown_name) : name;
    }

    static String displayIp(Context context, IncomingSessions.Session session) {
        String ip = session.clientIp;
        if (ip.isEmpty() || "-".equals(ip)) {
            return "?";
        }
        return "127.0.0.1".equals(ip) ? context.getString(R.string.incoming_via_cloudflare) : ip;
    }

    @SuppressWarnings("deprecation")
    static Notification build(Context context, List<IncomingSessions.Session> sessions) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.incoming_channel), NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(context.getString(R.string.incoming_channel_description));
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_DEFAULT);
        }

        String title;
        String text;
        IncomingSessions.Session first = sessions.get(0);
        if (sessions.size() == 1) {
            title = context.getString(R.string.incoming_title, displayName(context, first));
            String since = first.startMillis > 0
                    ? android.text.format.DateFormat.getTimeFormat(context).format(new Date(first.startMillis)) : "?";
            text = context.getString(R.string.incoming_notification_text, displayIp(context, first), since);
        } else {
            title = context.getString(R.string.incoming_title_many, sessions.size());
            StringBuilder names = new StringBuilder();
            for (IncomingSessions.Session session : sessions) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(displayName(context, session));
            }
            text = context.getString(R.string.incoming_notification_text_many, names.toString());
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent disconnect = new Intent(context, IncomingDisconnectReceiver.class)
                .setAction(IncomingDisconnectReceiver.ACTION_DISCONNECT);
        PendingIntent disconnectIntent = PendingIntent.getBroadcast(context, 10, disconnect, flags);
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openIntent = PendingIntent.getActivity(context, 11, open, flags);

        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(first.startMillis > 0 ? first.startMillis : System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.notification_disconnect), disconnectIntent).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }
}
