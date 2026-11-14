package com.lemonrsh.phoneshare;

import android.content.Intent;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session settings understood by the bundled scrcpy v5.0.1 server and the
 * Android viewer. This intentionally contains server options that have a real
 * implementation here, rather than desktop-only flags that this app cannot use.
 */
public final class ScrcpySessionOptions {
    public static final String SERVER_VERSION = "5.0.1";
    public static final int SERVER_PORT = 7007;

    private static final String EXTRA_PREFIX = "com.lemonrsh.phoneshare.session.";
    private static final String EXTRA_PRESENT = EXTRA_PREFIX + "present";
    private static final String EXTRA_AUDIO = EXTRA_PREFIX + "audio";
    private static final String EXTRA_NO_CONTROL = EXTRA_PREFIX + "no_control";
    private static final String EXTRA_POWER_ON = EXTRA_PREFIX + "power_on";
    private static final String EXTRA_TURN_SCREEN_OFF = EXTRA_PREFIX + "turn_screen_off";
    private static final String EXTRA_POWER_OFF_ON_CLOSE = EXTRA_PREFIX + "power_off_on_close";
    private static final String EXTRA_STAY_AWAKE = EXTRA_PREFIX + "stay_awake";
    private static final String EXTRA_SHOW_TOUCHES = EXTRA_PREFIX + "show_touches";
    private static final String EXTRA_MAX_SIZE = EXTRA_PREFIX + "max_size";
    private static final String EXTRA_VIDEO_BIT_RATE = EXTRA_PREFIX + "video_bit_rate";
    private static final String EXTRA_MAX_FPS = EXTRA_PREFIX + "max_fps";
    private static final String EXTRA_FLOATING_WINDOW = EXTRA_PREFIX + "floating_window";
    private static final String EXTRA_NAV_BAR = EXTRA_PREFIX + "nav_bar";
    private static final String EXTRA_VIDEO_CODEC = EXTRA_PREFIX + "video_codec";
    private static final String EXTRA_AUDIO_CODEC = EXTRA_PREFIX + "audio_codec";
    private static final String EXTRA_CLIPBOARD = EXTRA_PREFIX + "clipboard_autosync";
    private static final String EXTRA_START_APP = EXTRA_PREFIX + "start_app";
    private static final String EXTRA_CAMERA_SOURCE = EXTRA_PREFIX + "camera_source";
    private static final String EXTRA_TIME_LIMIT = EXTRA_PREFIX + "time_limit";
    private static final String EXTRA_LOG_LEVEL = EXTRA_PREFIX + "log_level";
    private static final String EXTRA_ARGS = EXTRA_PREFIX + "extra_args";

    public boolean audioEnabled;
    public boolean noControl;
    public boolean powerOn = true;
    public boolean turnScreenOffOnConnect;
    public boolean powerOffOnClose;
    public boolean stayAwake;
    public boolean showTouches;
    public boolean floatingWindow;
    public boolean navBar;
    public int maxSize = 854;
    public int videoBitRate = 1_024_000;
    public int maxFps = 30;
    /** "h264", "h265" or "av1". */
    public String videoCodec = "h264";
    /** "aac", "opus", "flac" or "raw". */
    public String audioCodec = "aac";
    public boolean clipboardAutosync;
    /** App to start on the target once connected (scrcpy --start-app), or empty. */
    public String startApp = "";
    public boolean cameraSource;
    /** Disconnect after this many seconds; 0 = no limit. */
    public int timeLimitSeconds;
    public String logLevel = "info";
    /** Further validated server arguments (key to value), see {@link AdvancedOptions}. */
    public final Map<String, String> extraArgs = new LinkedHashMap<>();

    public ScrcpySessionOptions copy() {
        ScrcpySessionOptions copy = new ScrcpySessionOptions();
        copy.audioEnabled = audioEnabled;
        copy.noControl = noControl;
        copy.powerOn = powerOn;
        copy.turnScreenOffOnConnect = turnScreenOffOnConnect;
        copy.powerOffOnClose = powerOffOnClose;
        copy.stayAwake = stayAwake;
        copy.showTouches = showTouches;
        copy.floatingWindow = floatingWindow;
        copy.navBar = navBar;
        copy.maxSize = maxSize;
        copy.videoBitRate = videoBitRate;
        copy.maxFps = maxFps;
        copy.videoCodec = videoCodec;
        copy.audioCodec = audioCodec;
        copy.clipboardAutosync = clipboardAutosync;
        copy.startApp = startApp;
        copy.cameraSource = cameraSource;
        copy.timeLimitSeconds = timeLimitSeconds;
        copy.logLevel = logLevel;
        copy.extraArgs.putAll(extraArgs);
        return copy;
    }

    public void putInto(Intent intent) {
        intent.putExtra(EXTRA_PRESENT, true);
        intent.putExtra(EXTRA_AUDIO, audioEnabled);
        intent.putExtra(EXTRA_NO_CONTROL, noControl);
        intent.putExtra(EXTRA_POWER_ON, powerOn);
        intent.putExtra(EXTRA_TURN_SCREEN_OFF, turnScreenOffOnConnect);
        intent.putExtra(EXTRA_POWER_OFF_ON_CLOSE, powerOffOnClose);
        intent.putExtra(EXTRA_STAY_AWAKE, stayAwake);
        intent.putExtra(EXTRA_SHOW_TOUCHES, showTouches);
        intent.putExtra(EXTRA_MAX_SIZE, maxSize);
        intent.putExtra(EXTRA_VIDEO_BIT_RATE, videoBitRate);
        intent.putExtra(EXTRA_MAX_FPS, maxFps);
        intent.putExtra(EXTRA_FLOATING_WINDOW, floatingWindow);
        intent.putExtra(EXTRA_NAV_BAR, navBar);
        intent.putExtra(EXTRA_VIDEO_CODEC, videoCodec);
        intent.putExtra(EXTRA_AUDIO_CODEC, audioCodec);
        intent.putExtra(EXTRA_CLIPBOARD, clipboardAutosync);
        intent.putExtra(EXTRA_START_APP, startApp);
        intent.putExtra(EXTRA_CAMERA_SOURCE, cameraSource);
        intent.putExtra(EXTRA_TIME_LIMIT, timeLimitSeconds);
        intent.putExtra(EXTRA_LOG_LEVEL, logLevel);
        Bundle args = new Bundle();
        for (Map.Entry<String, String> entry : extraArgs.entrySet()) {
            args.putString(entry.getKey(), entry.getValue());
        }
        intent.putExtra(EXTRA_ARGS, args);
    }

    public static boolean hasExtras(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_PRESENT, false);
    }

    public static ScrcpySessionOptions fromIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_PRESENT, false)) {
            return new ScrcpySessionOptions();
        }
        Bundle extras = intent.getExtras();
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        options.audioEnabled = extras.getBoolean(EXTRA_AUDIO, false);
        options.noControl = extras.getBoolean(EXTRA_NO_CONTROL, false);
        options.powerOn = extras.getBoolean(EXTRA_POWER_ON, true);
        options.turnScreenOffOnConnect = extras.getBoolean(EXTRA_TURN_SCREEN_OFF, false);
        options.powerOffOnClose = extras.getBoolean(EXTRA_POWER_OFF_ON_CLOSE, false);
        options.stayAwake = extras.getBoolean(EXTRA_STAY_AWAKE, false);
        options.showTouches = extras.getBoolean(EXTRA_SHOW_TOUCHES, false);
        options.maxSize = extras.getInt(EXTRA_MAX_SIZE, 1280);
        options.videoBitRate = extras.getInt(EXTRA_VIDEO_BIT_RATE, 2_048_000);
        options.maxFps = extras.getInt(EXTRA_MAX_FPS, 60);
        options.floatingWindow = extras.getBoolean(EXTRA_FLOATING_WINDOW, false);
        options.navBar = extras.getBoolean(EXTRA_NAV_BAR, false);
        options.videoCodec = extras.getString(EXTRA_VIDEO_CODEC, "h264");
        options.audioCodec = extras.getString(EXTRA_AUDIO_CODEC, "aac");
        options.clipboardAutosync = extras.getBoolean(EXTRA_CLIPBOARD, false);
        options.startApp = extras.getString(EXTRA_START_APP, "");
        options.cameraSource = extras.getBoolean(EXTRA_CAMERA_SOURCE, false);
        options.timeLimitSeconds = extras.getInt(EXTRA_TIME_LIMIT, 0);
        options.logLevel = extras.getString(EXTRA_LOG_LEVEL, "info");
        Bundle args = extras.getBundle(EXTRA_ARGS);
        if (args != null) {
            for (String key : args.keySet()) {
                String value = args.getString(key);
                if (value != null) {
                    options.extraArgs.put(key, value);
                }
            }
        }
        return options;
    }
}
