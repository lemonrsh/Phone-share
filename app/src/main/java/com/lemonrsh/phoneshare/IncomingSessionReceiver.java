package com.lemonrsh.phoneshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Told by the scrcpy server on this phone (SessionBeacon, running as the shell user through
 * {@code am broadcast}) that another device connected or disconnected. Protected by the DUMP
 * permission in the manifest, so ordinary apps cannot fake it.
 */
public class IncomingSessionReceiver extends BroadcastReceiver {
    static final String ACTION_STARTED = "com.lemonrsh.phoneshare.action.SESSION_STARTED";
    static final String ACTION_ENDED = "com.lemonrsh.phoneshare.action.SESSION_ENDED";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String action = intent.getAction();
        IncomingSessions.Session session = sessionFrom(intent);
        if (ACTION_STARTED.equals(action)) {
            DiagnosticLog.i("incoming", "Connected: " + (session == null ? "?" : session.clientIp));
            if (IncomingSessionService.refresh(session)) {
                return;
            }
            if (IncomingSessionService.start(app, session)) {
                return;
            }
            // Android did not let the service start from the background (Android 12+): show the
            // notification anyway. It is removed by SESSION_ENDED or when the app is opened.
            List<IncomingSessions.Session> sessions = new ArrayList<>();
            if (session != null) {
                sessions.add(session);
            }
            IncomingNotifier.show(app, sessions);
        } else if (ACTION_ENDED.equals(action)) {
            DiagnosticLog.i("incoming", "Disconnected: " + (session == null ? "?" : session.clientIp));
            if (IncomingSessionService.refresh(null)) {
                return;
            }
            rescanAndShow(app, goAsync());
        }
    }

    /** Shows what is still connected (e.g. a second device), or removes the notification. */
    static void rescanAndShow(final Context app, final PendingResult pending) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            final List<IncomingSessions.Session> sessions = IncomingSessions.scanNow();
            handler.post(() -> {
                IncomingNotifier.show(app, sessions);
                if (pending != null) {
                    pending.finish();
                }
            });
        }, "incoming-rescan");
        thread.setDaemon(true);
        thread.start();
    }

    static IncomingSessions.Session sessionFrom(Intent intent) {
        if (intent == null || !intent.hasExtra("port")) {
            return null;
        }
        int port = intent.getIntExtra("port", 0);
        if (port < IncomingSessions.FIRST_PORT || port >= IncomingSessions.FIRST_PORT + IncomingSessions.PORT_COUNT) {
            return null;
        }
        String ip = intent.getStringExtra("ip");
        String name = intent.getStringExtra("name");
        return new IncomingSessions.Session(port, ip == null ? "" : ip, name == null ? "" : name,
                intent.getLongExtra("start", 0));
    }
}
