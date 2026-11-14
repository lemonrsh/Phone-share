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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "Share this phone over the internet" (Cloudflare test). Runs on the phone that will be
 * mirrored: a PIN-protected WebSocket bridge to its own ADB (5555) and scrcpy (7007) ports,
 * published by a cloudflared quick tunnel. Shows the share code (address#PIN) in the app and
 * in its notification. Needs ADB over Wi-Fi on port 5555, like a direct connection.
 */
public class ShareService extends Service {
    public static final String ACTION_START = "org.las2mile.scrcpy.action.START_SHARE";
    public static final String ACTION_STOP = "org.las2mile.scrcpy.action.STOP_SHARE";

    private static final String TAG = "ShareService";
    private static final String CHANNEL_ID = "cloud_share";
    private static final int NOTIFICATION_ID = 2;

    enum Phase { IDLE, STARTING, WAITING, ONLINE, ERROR }

    interface Listener {
        void onShareStateChanged();
    }

    // State shown by MainActivity; only changed on the main thread.
    private static Phase phase = Phase.IDLE;
    private static String address;
    private static String pin;
    private static String message;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WsBridgeServer bridge;
    private Cloudflared cloudflared;
    private PowerManager.WakeLock wakeLock;
    private int generation;

    static Phase phase() {
        return phase;
    }

    static String address() {
        return address;
    }

    static String pin() {
        return pin;
    }

    static String message() {
        return message;
    }

    /** The text the other phone pastes into its address box. */
    static String shareCode() {
        return address == null || pin == null ? null : address + "#" + pin;
    }

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void setState(Phase newPhase, String newAddress, String newPin, String newMessage) {
        phase = newPhase;
        address = newAddress;
        pin = newPin;
        message = newMessage;
        for (Listener listener : LISTENERS) {
            listener.onShareStateChanged();
        }
    }

    static String newPin() {
        SecureRandom random = new SecureRandom();
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSharing(null);
            return START_NOT_STICKY;
        }
        if (!enterForeground()) {
            setState(Phase.ERROR, null, null, "Could not start the background service.");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (phase == Phase.STARTING || phase == Phase.WAITING || phase == Phase.ONLINE) {
            return START_NOT_STICKY;
        }
        startSharing();
        return START_NOT_STICKY;
    }

    private void startSharing() {
        final int myGeneration = ++generation;
        final String newPin = newPin();
        setState(Phase.STARTING, null, newPin, getString(R.string.share_checking_adb));
        updateNotification();
        acquireWakeLock();
        new Thread(() -> {
            if (!Cloudflared.isBundled(this)) {
                fail(myGeneration, getString(R.string.share_not_bundled));
                return;
            }
            if (!LocalAdb.isListening()) {
                fail(myGeneration, getString(R.string.share_adb_off));
                return;
            }
            try {
                Map<String, Integer> targets = new HashMap<>();
                targets.put(CloudTunnel.TARGET_ADB, LocalAdb.PORT);
                targets.put(CloudTunnel.TARGET_SCRCPY, ScrcpySessionOptions.SERVER_PORT);
                final WsBridgeServer newBridge = new WsBridgeServer(newPin, targets);
                newBridge.start();
                final Cloudflared newCloudflared = new Cloudflared(this);
                mainHandler.post(() -> {
                    if (myGeneration != generation) {
                        newBridge.stop();
                        return;
                    }
                    bridge = newBridge;
                    cloudflared = newCloudflared;
                    setState(Phase.STARTING, null, newPin, getString(R.string.share_starting_tunnel));
                    updateNotification();
                });
                newCloudflared.start(newBridge.getPort(), new Cloudflared.Listener() {
                    @Override
                    public void onAddress(String host) {
                        mainHandler.post(() -> {
                            if (myGeneration == generation) {
                                setState(Phase.WAITING, host, newPin, getString(R.string.share_waiting));
                                updateNotification();
                            }
                        });
                    }

                    @Override
                    public void onOnline() {
                        mainHandler.post(() -> {
                            if (myGeneration == generation) {
                                setState(Phase.ONLINE, address, newPin, getString(R.string.share_online));
                                updateNotification();
                            }
                        });
                    }

                    @Override
                    public void onExit(String reason) {
                        if (reason != null) {
                            fail(myGeneration, reason);
                        }
                    }
                });
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not start sharing", e);
                DiagnosticLog.e("share", "Could not start sharing", e);
                fail(myGeneration, getString(R.string.share_failed, e.getMessage()));
            }
        }, "share-start").start();
    }

    private void fail(final int myGeneration, final String reason) {
        mainHandler.post(() -> {
            if (myGeneration == generation) {
                stopSharing(reason);
            }
        });
    }

    private void stopSharing(String error) {
        ++generation;
        if (cloudflared != null) {
            cloudflared.stop();
            cloudflared = null;
        }
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        releaseWakeLock();
        setState(error == null ? Phase.IDLE : Phase.ERROR, null, null, error);
        if (error != null) {
            DiagnosticLog.i("share", "Sharing stopped: " + error);
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (cloudflared != null || bridge != null) {
            ++generation;
            if (cloudflared != null) {
                cloudflared.stop();
            }
            if (bridge != null) {
                bridge.stop();
            }
            cloudflared = null;
            bridge = null;
            setState(Phase.IDLE, null, null, null);
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scrcpy:share");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "No wake lock", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // ignore
            }
            wakeLock = null;
        }
    }

    @SuppressLint("InlinedApi")
    private boolean enterForeground() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start the foreground service", e);
            return false;
        }
    }

    private void updateNotification() {
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
                        getString(R.string.share_channel), NotificationManager.IMPORTANCE_LOW);
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
        Intent stopIntent = new Intent(this, ShareService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 1, openIntent, pendingFlags);
        String code = shareCode();
        String text = phase == Phase.ONLINE && code != null
                ? getString(R.string.share_notification_code, code)
                : (message != null ? message : getString(R.string.share_starting_tunnel));
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.share_notification_title))
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.share_stop), stopPendingIntent).build())
                .build();
    }
}
