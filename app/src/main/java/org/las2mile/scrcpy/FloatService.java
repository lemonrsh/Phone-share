package org.las2mile.scrcpy;

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
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Hosts the floating mirror window. Runs as a foreground service so that the
 * system does not stop the session while another app is in front.
 */
public class FloatService extends Service implements ScrcpyClient.Listener, SurfaceHolder.Callback {
    public static final String ACTION_START = "org.las2mile.scrcpy.action.START_FLOATING";
    public static final String ACTION_STOP = "org.las2mile.scrcpy.action.STOP_FLOATING";
    public static final String EXTRA_HOST = "org.las2mile.scrcpy.extra.HOST";

    private static final String TAG = "FloatService";
    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_DARK_MODE = "dark_mode";
    private static final String CHANNEL_ID = "floating_mirror";
    private static final int NOTIFICATION_ID = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DisplayWindow displayWindow;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private boolean viewAdded;
    private ScrcpySessionOptions sessionOptions = new ScrcpySessionOptions();
    private AdbLauncher launcher;
    private ScrcpyClient client;
    private int remoteWidth = 1080;
    private int remoteHeight = 1920;

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

        HostAddress address = HostAddress.parse(intent.getStringExtra(EXTRA_HOST));
        if (address == null) {
            showToast("Invalid target address");
            stopSelf();
            return START_NOT_STICKY;
        }
        sessionOptions = ScrcpySessionOptions.fromIntent(intent);

        // A new start request replaces any running session.
        stopSession();
        if (!ensureWindow()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        displayWindow.showHint(getString(R.string.status_starting));
        displayWindow.setControlEnabled(!sessionOptions.noControl);
        displayWindow.setRemote(remoteWidth, remoteHeight);
        launch(address, sessionOptions.copy());
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
            showToast(error == null ? "Connection failed" : error);
            stopSelf();
            return;
        }
        client = new ScrcpyClient(address.host, options, session, this);
        SurfaceHolder holder = displayWindow.getSurfaceView().getHolder();
        if (holder.getSurface().isValid()) {
            client.setSurface(holder.getSurface());
        }
        client.start();
    }

    @Override
    public void onConnected(int width, int height) {
        if (displayWindow != null) {
            displayWindow.hideHintTip();
        }
        onVideoSizeChanged(width, height);
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        remoteWidth = width;
        remoteHeight = height;
        if (displayWindow != null) {
            displayWindow.setRemote(width, height);
        }
    }

    @Override
    public void onDisconnected(String message) {
        client = null;
        showToast(message);
        stopSelf();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (client != null) {
            client.setSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The decoder scales into the Surface.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (client != null) {
            client.setSurface(null);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (displayWindow != null) {
            displayWindow.setRemote(remoteWidth, remoteHeight);
        }
    }

    @Override
    public void onDestroy() {
        stopSession();
        removeWindow();
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
        if (viewAdded) {
            return true;
        }
        try {
            setupDisplay();
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            layoutParams = new WindowManager.LayoutParams();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutParams.type = legacyOverlayType();
            }
            // Not focusable: the keyboard and the back key keep working in the app underneath.
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            layoutParams.format = PixelFormat.TRANSLUCENT;
            layoutParams.gravity = Gravity.TOP | Gravity.START;
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            windowManager.addView(displayWindow, layoutParams);
            viewAdded = true;
            return true;
        } catch (RuntimeException e) {
            // Typically a missing "display over other apps" permission.
            Log.e(TAG, "Could not show the floating window", e);
            showToast("Could not show the floating window. Allow \"display over other apps\" for scrcpy.");
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static int legacyOverlayType() {
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void removeWindow() {
        if (viewAdded && windowManager != null && displayWindow != null) {
            try {
                windowManager.removeView(displayWindow);
            } catch (RuntimeException ignored) {
                // Already removed.
            }
        }
        viewAdded = false;
    }

    private void setupDisplay() {
        if (displayWindow != null) {
            return;
        }
        boolean darkMode = getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE).getBoolean(PREFERENCE_DARK_MODE, false);
        Context themedContext = new ContextThemeWrapper(this, darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        displayWindow = new DisplayWindow(themedContext);
        displayWindow.getSurfaceView().getHolder().addCallback(this);
        displayWindow.setCloseListener(v -> stopSelf());
        displayWindow.setMoveCallback((x, y) -> {
            if (layoutParams != null && windowManager != null && viewAdded) {
                layoutParams.x += Math.round(x);
                layoutParams.y += Math.round(y);
                try {
                    windowManager.updateViewLayout(displayWindow, layoutParams);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not move the floating window", e);
                }
            }
        });
        displayWindow.setActionCallback(actionType -> {
            if (client == null) {
                return;
            }
            switch (actionType) {
                case 0:
                    client.sendKeyEvent(4); // BACK
                    break;
                case 1:
                    client.sendKeyEvent(3); // HOME
                    break;
                case 2:
                    client.sendKeyEvent(187); // APP_SWITCH
                    break;
                default:
                    break;
            }
        });
        displayWindow.setOnDisplayTouchListener((view, motionEvent) -> client != null
                && client.sendTouch(motionEvent, displayWindow.getSurfaceWidth(), displayWindow.getSurfaceHeight()));
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
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.notification_stop), stopPendingIntent).build())
                .build();
    }
}
