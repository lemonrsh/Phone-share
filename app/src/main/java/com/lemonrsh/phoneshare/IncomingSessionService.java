package com.lemonrsh.phoneshare;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the "connected to this phone" notification up while another device mirrors this phone,
 * checks every few seconds that the session is still there (so the notification also goes away
 * when the server was killed without saying goodbye) and stops itself when nothing is connected.
 */
public class IncomingSessionService extends Service {
    private static final String TAG = "IncomingSessionService";
    private static final long POLL_MS = 3000;
    private static final long GRACE_MS = 8000;
    private static final int MISSES_TO_STOP = 2;

    /** Main thread only. */
    private static IncomingSessionService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "incoming-watch");
        thread.setDaemon(true);
        return thread;
    });
    private List<IncomingSessions.Session> sessions = new ArrayList<>();
    private long graceUntil;
    private int misses;
    private boolean scanning;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            scanNow();
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** Starts the service with this session (from a receiver or the app). False when not allowed. */
    static boolean start(Context context, IncomingSessions.Session session) {
        Intent intent = new Intent(context, IncomingSessionService.class);
        if (session != null) {
            intent.putExtra("port", session.port)
                    .putExtra("ip", session.clientIp)
                    .putExtra("name", session.clientName)
                    .putExtra("start", session.startMillis);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (Android 12+, app in the background).
            Log.w(TAG, "Could not start the connected-device service", e);
            DiagnosticLog.i("incoming", "Notification service not allowed in the background: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** When the service runs: adds the session (may be null) and checks again now. */
    static boolean refresh(IncomingSessions.Session session) {
        IncomingSessionService service = instance;
        if (service == null) {
            return false;
        }
        if (session != null) {
            service.add(session);
            service.render();
        }
        service.scanNow();
        return true;
    }

    static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        IncomingSessions.Session session = IncomingSessionReceiver.sessionFrom(intent);
        if (session != null) {
            add(session);
        }
        graceUntil = SystemClock.elapsedRealtime() + GRACE_MS;
        if (!goForeground()) {
            IncomingNotifier.show(this, sessions);
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_NOT_STICKY;
    }

    private void add(IncomingSessions.Session session) {
        for (IncomingSessions.Session known : sessions) {
            if (known.port == session.port) {
                return;
            }
        }
        sessions.add(session);
    }

    private boolean goForeground() {
        try {
            Notification notification = IncomingNotifier.build(this, placeholderIfEmpty());
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(IncomingNotifier.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(IncomingNotifier.NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start the foreground service", e);
            return false;
        }
    }

    private List<IncomingSessions.Session> placeholderIfEmpty() {
        if (!sessions.isEmpty()) {
            return sessions;
        }
        List<IncomingSessions.Session> list = new ArrayList<>();
        list.add(new IncomingSessions.Session(IncomingSessions.FIRST_PORT, "", "", System.currentTimeMillis()));
        return list;
    }

    private void scanNow() {
        if (scanning) {
            return;
        }
        scanning = true;
        executor.execute(() -> {
            final List<IncomingSessions.Session> found = IncomingSessions.scanNow();
            handler.post(() -> {
                scanning = false;
                if (instance != this) {
                    return;
                }
                onScan(found);
            });
        });
    }

    private void onScan(List<IncomingSessions.Session> found) {
        if (found.isEmpty()) {
            if (SystemClock.elapsedRealtime() < graceUntil) {
                return;
            }
            if (++misses >= MISSES_TO_STOP) {
                finish();
            }
            return;
        }
        misses = 0;
        if (!found.equals(sessions)) {
            sessions = new ArrayList<>(found);
            render();
        }
    }

    private void render() {
        if (!sessions.isEmpty()) {
            IncomingNotifier.show(this, sessions);
        }
    }

    private void finish() {
        handler.removeCallbacks(poll);
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForegroundCompat();
        }
        IncomingNotifier.cancel(this);
        stopSelf();
        if (instance == this) {
            instance = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        executor.shutdownNow();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
