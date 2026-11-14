package org.las2mile.scrcpy;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.WindowManager;

public class FloatService extends Service {
    private static final String TAG = "FloatService";
    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_DARK_MODE = "dark_mode";

    private DisplayWindow displayWindow;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private ScrcpyHost scrcpyHost;
    private ScrcpySessionOptions sessionOptions;
    private int width;
    private int height;
    private boolean viewAdded;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String ip = intent.getStringExtra("ip");
        width = intent.getIntExtra("w", 1080);
        height = intent.getIntExtra("h", 1920);
        sessionOptions = ScrcpySessionOptions.fromIntent(intent);
        Log.d(TAG, "Starting overlay for " + ip + " at max size " + sessionOptions.maxSize);
        displayWindow.setControlEnabled(!sessionOptions.noControl);
        displayWindow.setRemote(width, height);

        new Thread(() -> startCopy(ip), "scrcpy-overlay-start").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setupDisplay();
        windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        layoutParams = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.verticalMargin = 0;
        layoutParams.horizontalMargin = 0;
        windowManager.addView(displayWindow, layoutParams);
        viewAdded = true;
    }

    private void startCopy(String ip) {
        scrcpyHost = new ScrcpyHost();
        scrcpyHost.setConnectCallBack((remoteWidth, remoteHeight) -> {
            displayWindow.setRemote((int) remoteWidth, (int) remoteHeight);
            displayWindow.hideHintTip();
        });
        scrcpyHost.connect(getApplicationContext(), ip, width, height,
                sessionOptions.videoBitRate, sessionOptions, displayWindow.getDisplaySurface());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (displayWindow != null) {
            displayWindow.setRemote(width, height);
        }
    }

    @Override
    public void onDestroy() {
        if (viewAdded && windowManager != null && displayWindow != null) {
            try {
                windowManager.removeView(displayWindow);
            } catch (IllegalArgumentException ignored) {
                // The overlay was already removed by its close button.
            }
            viewAdded = false;
        }
        if (scrcpyHost != null) {
            scrcpyHost.destroy();
            scrcpyHost = null;
        }
        super.onDestroy();
    }

    private void setupDisplay() {
        boolean darkMode = getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE).getBoolean(PREFERENCE_DARK_MODE, false);
        Context themedContext = new ContextThemeWrapper(getApplicationContext(),
                darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        displayWindow = new DisplayWindow(themedContext);
        displayWindow.setCloseListener(v -> {
            Log.d(TAG, "Closing floating window");
            stopSelf();
        });
        displayWindow.setMoveCallback((x, y) -> {
            if (layoutParams != null && windowManager != null && viewAdded) {
                layoutParams.x += Math.round(x);
                layoutParams.y += Math.round(y);
                windowManager.updateViewLayout(displayWindow, layoutParams);
            }
        });
        displayWindow.setActionCallback(actionType -> {
            if (scrcpyHost == null) {
                return;
            }
            switch (actionType) {
                case 0:
                    scrcpyHost.keyEvent(4);
                    break;
                case 1:
                    scrcpyHost.keyEvent(3);
                    break;
                case 2:
                    scrcpyHost.keyEvent(187);
                    break;
                default:
                    break;
            }
        });
        displayWindow.setOnDisplayTouchListener((view, motionEvent) -> scrcpyHost != null
                && scrcpyHost.touch(motionEvent, displayWindow.getSurfaceWidth(), displayWindow.getSurfaceHeight()));
    }
}
