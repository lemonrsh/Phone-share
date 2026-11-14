package com.lemonrsh.phoneshare;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.Toast;

import com.lemonrsh.phoneshare.pip.FloatingPip;

/**
 * Hosts the YouTube-style floating mirror window. Runs as a foreground service
 * so that the system does not stop the session while another app is in front.
 * The window has no buttons: swiping it onto the bottom target (or flinging it
 * down) closes it and stops the service.
 */
public class FloatService extends Service implements ScrcpyClient.Listener, FloatingPip.Callbacks {
    public static final String ACTION_START = "com.lemonrsh.phoneshare.action.START_FLOATING";
    public static final String ACTION_STOP = "com.lemonrsh.phoneshare.action.STOP_FLOATING";
    /** Continue the live session handed over by the full-screen viewer (see SessionHandoff). */
    public static final String ACTION_ADOPT = "com.lemonrsh.phoneshare.action.ADOPT_SESSION";
    public static final String EXTRA_HOST = "com.lemonrsh.phoneshare.extra.HOST";

    private static final String TAG = "FloatService";
    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_DARK_MODE = "dark_mode";
    private static final String CHANNEL_ID = "floating_mirror";
    private static final int NOTIFICATION_ID = 1;

    private static final long ERROR_AUTO_CLOSE_MS = 60_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = this::stopSelf;
    private FloatingPip pip;
    private boolean pipControlEnabled;
    private Surface surface;
    private String currentHost;
    private ScrcpySessionOptions sessionOptions = new ScrcpySessionOptions();
    private AdbLauncher launcher;
    private ScrcpyClient client;
    private int remoteWidth = 1080;
    private int remoteHeight = 1920;
    private ClipboardSync clipboardSync;
    private String notificationText;

    @Override
    public void onCreate() {
        super.onCreate();
        LogActivity.installCrashRecorder(this);
        clipboardSync = new ClipboardSync(this, () -> client);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must be called promptly after startForegroundService(), whatever the intent says.
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ADOPT.equals(intent.getAction())) {
            return adoptSession();
        }

        HostAddress address = HostAddress.parse(intent.getStringExtra(EXTRA_HOST));
        if (address == null) {
            showToast("Invalid target address");
            stopSelf();
            return START_NOT_STICKY;
        }
        sessionOptions = ScrcpySessionOptions.fromIntent(intent);
        currentHost = address.toString();

        // A new start request replaces any running session.
        mainHandler.removeCallbacks(autoClose);
        stopSession();
        if (pip != null && pipControlEnabled == sessionOptions.noControl) {
            // The gesture layout depends on whether remote control is enabled.
            removeWindow();
        }
        if (!ensureWindow()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        pip.setStatus(getString(R.string.status_starting));
        pip.showLoading(true);
        SessionState.connecting(currentHost);
        updateNotification(getString(R.string.notification_connecting, currentHost));
        launch(address, sessionOptions.copy());
        return START_NOT_STICKY;
    }

    private int adoptSession() {
        SessionHandoff.Item item = SessionHandoff.take();
        if (item == null || !item.client.isConnected()) {
            if (item != null) {
                item.client.stop();
                SessionState.disconnected(null);
            }
            if (client == null && launcher == null) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        mainHandler.removeCallbacks(autoClose);
        stopSession();
        sessionOptions = item.options.copy();
        currentHost = item.host;
        if (pip != null && pipControlEnabled == sessionOptions.noControl) {
            removeWindow();
        }
        remoteWidth = item.client.getVideoWidth() > 0 ? item.client.getVideoWidth() : remoteWidth;
        remoteHeight = item.client.getVideoHeight() > 0 ? item.client.getVideoHeight() : remoteHeight;
        if (!ensureWindow()) {
            item.client.stop();
            SessionState.disconnected("Could not show the floating window");
            stopSelf();
            return START_NOT_STICKY;
        }
        client = item.client;
        client.setListener(this);
        if (surface != null && surface.isValid()) {
            client.setSurface(surface);
        }
        pip.showLoading(false);
        pip.setVideoSize(remoteWidth, remoteHeight);
        DiagnosticLog.i("app", "Session moved to the floating window");
        updateNotification(getString(R.string.notification_connected,
                SessionState.describe(currentHost, client.getDeviceName())));
        if (sessionOptions.clipboardAutosync && !sessionOptions.noControl) {
            clipboardSync.start();
        }
        return START_NOT_STICKY;
    }

    private void launch(HostAddress address, ScrcpySessionOptions options) {
        final AdbLauncher currentLauncher = new AdbLauncher();
        launcher = currentLauncher;
        Thread thread = new Thread(() -> {
            AdbLauncher.Session session = null;
            String error = null;
            try {
                session = currentLauncher.launch(getApplicationContext(), address, options);
            } catch (AdbLauncher.LaunchException e) {
                Log.w(TAG, "Launch failed", e);
                error = e.getMessage();
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected launch failure", e);
                error = "Unexpected error: " + e;
            }
            final AdbLauncher.Session result = session;
            final String message = error;
            // Toasts and views must only be touched on the main thread.
            mainHandler.post(() -> onLaunchFinished(currentLauncher, result, message, address, options));
        }, "scrcpy-overlay-connect");
        thread.start();
    }

    private void onLaunchFinished(AdbLauncher finishedLauncher, AdbLauncher.Session session, String error,
                                  HostAddress address, ScrcpySessionOptions options) {
        if (launcher != finishedLauncher) {
            if (session != null) {
                session.closeAsync();
            }
            return;
        }
        launcher = null;
        if (session == null) {
            String reason = error == null ? "Connection failed" : error;
            SessionState.disconnected(reason);
            showFailure(reason);
            return;
        }
        client = new ScrcpyClient(address.host, options, session, this);
        if (surface != null && surface.isValid()) {
            client.setSurface(surface);
        }
        client.start();
    }

    @Override
    public void onConnected(int width, int height) {
        if (pip != null) {
            pip.showLoading(false);
        }
        String name = client == null ? null : client.getDeviceName();
        String who = SessionState.describe(currentHost, name);
        SessionState.connected(currentHost, name);
        showToast(getString(R.string.toast_connected, who));
        updateNotification(getString(R.string.notification_connected, who));
        if (sessionOptions.clipboardAutosync && !sessionOptions.noControl) {
            clipboardSync.start();
        }
        onVideoSizeChanged(width, height);
    }

    @Override
    public void onDeviceClipboard(String text) {
        clipboardSync.onDeviceClipboard(text);
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        remoteWidth = width;
        remoteHeight = height;
        if (pip != null) {
            pip.setVideoSize(width, height);
        }
    }

    @Override
    public void onDisconnected(String message) {
        client = null;
        clipboardSync.stop();
        SessionState.disconnected(message);
        if (pip != null) {
            // Without a window, showFailure() already shows the message as a toast.
            showToast(message == null ? getString(R.string.toast_disconnected)
                    : getString(R.string.toast_disconnected_reason, message));
        }
        updateNotification(getString(R.string.notification_disconnected));
        showFailure(message);
    }

    /**
     * Keeps the floating window open with the error instead of closing it with a
     * short toast, so the reason can actually be read. Swipe away (or wait) to close.
     */
    private void showFailure(String message) {
        String text = message == null ? "Disconnected." : message;
        Log.w(TAG, "Session failed: " + text);
        DiagnosticLog.i("app", "Floating window shows error: " + text);
        if (pip == null) {
            showToast(text);
            stopSelf();
            return;
        }
        pip.showError(text);
        mainHandler.removeCallbacks(autoClose);
        mainHandler.postDelayed(autoClose, ERROR_AUTO_CLOSE_MS);
    }

    // ---------------------------------------------------------------- FloatingPip.Callbacks

    @Override
    public void onSurfaceAvailable(Surface newSurface) {
        surface = newSurface;
        if (client != null) {
            client.setSurface(newSurface);
        }
    }

    @Override
    public void onSurfaceDestroyed() {
        surface = null;
        if (client != null) {
            // Blocks until the decoder no longer renders into the old surface.
            client.setSurface(null);
        }
    }

    @Override
    public void onRemoteTouch(MotionEvent event, int videoWidth, int videoHeight) {
        if (client != null) {
            client.sendTouch(event, videoWidth, videoHeight);
        }
    }

    @Override
    public void onKey(int keycode) {
        if (client != null) {
            client.sendKeyEvent(keycode);
        }
    }

    @Override
    public void onErrorTapped() {
        Intent intent = new Intent(this, LogActivity.class)
                .putExtra(LogActivity.EXTRA_SECTION, LogActivity.SECTION_CONNECTION_LOG)
                .putExtra(LogActivity.EXTRA_HOST, currentHost)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open the log", e);
            return;
        }
        // The log screen replaces the failed window.
        stopSelf();
    }

    @Override
    public void onDismissed() {
        stopSelf();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (pip != null) {
            pip.onConfigurationChanged();
        }
    }

    @Override
    public void onDestroy() {
        boolean active = SessionState.getStatus() == SessionState.Status.CONNECTED
                || SessionState.getStatus() == SessionState.Status.CONNECTING;
        boolean hadSession = client != null || launcher != null;
        stopSession();
        removeWindow();
        if (clipboardSync != null) {
            clipboardSync.stop();
        }
        if (hadSession && active) {
            // Closed by the user (swipe away or notification).
            SessionState.disconnected(null);
            showToast(getString(R.string.toast_disconnected));
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void stopSession() {
        if (launcher != null) {
            launcher.cancel();
            launcher = null;
        }
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    private boolean ensureWindow() {
        if (pip != null) {
            return true;
        }
        boolean darkMode = getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE).getBoolean(PREFERENCE_DARK_MODE, false);
        Context themedContext = new ContextThemeWrapper(this, darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        FloatingPip window = new FloatingPip(themedContext, !sessionOptions.noControl, this);
        window.setVideoSize(remoteWidth, remoteHeight);
        try {
            window.show();
        } catch (RuntimeException e) {
            // Typically a missing "display over other apps" permission.
            Log.e(TAG, "Could not show the floating window", e);
            window.remove();
            showToast("Could not show the floating window. Allow \"display over other apps\" for scrcpy.");
            return false;
        }
        pip = window;
        pipControlEnabled = !sessionOptions.noControl;
        return true;
    }

    private void removeWindow() {
        if (pip != null) {
            pip.remove();
            pip = null;
        }
        surface = null;
    }

    private void showToast(String message) {
        if (message == null) {
            return;
        }
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    @SuppressLint("InlinedApi")
    private boolean enterForeground() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start the foreground service", e);
            showToast("Could not start the floating window: " + e.getMessage());
            return false;
        }
    }

    private void updateNotification(String text) {
        notificationText = text;
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not update the notification", e);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent stopIntent = new Intent(this, FloatService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingFlags);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags);

        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title)) // "Phone Share is running"
                .setContentText(notificationText != null ? notificationText : getString(R.string.notification_text))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.notification_stop), stopPendingIntent).build())
                .build();
    }
}
