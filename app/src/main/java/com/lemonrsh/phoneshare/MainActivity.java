package com.lemonrsh.phoneshare;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

public class MainActivity extends Activity implements ScrcpyClient.Listener, SensorEventListener,
        SurfaceHolder.Callback, SessionState.Listener {
    private static final String TAG = "MainActivity";
    private static final String PREFERENCE_KEY = "default";
    /** v2: 854x480 (480p, recommended) was inserted at index 5 (see migrateMovedOptions). */
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution_v2";
    private static final String PREFERENCE_SPINNER_RESOLUTION_OLD = "spinner_resolution";
    private static final int RESOLUTION_480P = 5;
    // Recommended defaults: 480p, 1 Mbps, 30 fps, H.264, audio on and playing on both devices.
    private static final int DEFAULT_BITRATE_INDEX = 3;   // options_bitrate_keys: 1 Mbps
    private static final int DEFAULT_FPS_INDEX = 0;       // options_fps_values: 30
    private static final boolean DEFAULT_AUDIO = true;
    private static final boolean DEFAULT_AUDIO_DUP = true;
    /** Bumped when the recommended defaults change: they are applied once on update. */
    private static final String PREFERENCE_DEFAULTS_VERSION = "defaults_version";
    private static final int DEFAULTS_VERSION = 2;
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";
    private static final String PREFERENCE_SPINNER_FPS = "spinner_fps";
    private static final String PREFERENCE_DARK_MODE = "dark_mode";
    private static final String PREFERENCE_FLOATING_WINDOW = "floating_window";
    private static final String PREFERENCE_LOCAL_ADB_PERSIST = "local_adb_persist";
    private static final String PREFERENCE_AUTO_FLOAT = "auto_float";
    private static final String PREFERENCE_AUDIO_DUP = "Audio Dup";
    private static final String PREFERENCE_KEEP_ACTIVE = "Keep Active";
    private static final String PREFERENCE_ADB_PROMPT_OFF = "adb_prompt_off";
    private static final int REQUEST_NOTIFICATIONS = 1;
    /** The start-up ADB question is asked at most once per app launch. */
    private static boolean adbPromptChecked;
    /** The start-up floating-window permission question is asked at most once per app launch. */
    private static boolean overlayPromptChecked;
    private static boolean batteryPromptChecked;
    private boolean resumeAfterBatteryPrompt;
    /** Back from the overlay settings opened by the start-up question: continue with the ADB question. */
    private boolean resumeStartupPrompts;
    /** The settings page (gear button) is shown instead of the main page. */
    private boolean settingsOpen;
    private static final String STATE_SETTINGS_OPEN = "settings_open";
    private static final String PREFERENCE_ROOT_SETUP_TIME = "root_setup_time";
    private static final String PREFERENCE_ROOT_SETUP_DONE = "root_setup_done";
    private static final String PREFERENCE_ROOT_SETUP_TOTAL = "root_setup_total";
    /** Last "no root" message from the root setup, shown with the live status. */
    private String rootSetupError;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SETTINGS_OPEN, settingsOpen && !mirroring);
    }
    private static final long BACK_PRESS_INTERVAL_MS = 1_500;

    private ScrcpySessionOptions sessionOptions = new ScrcpySessionOptions();
    private HostAddress hostAddress;

    // Connection state (main thread only).
    private AdbLauncher launcher;
    private ScrcpyClient client;
    private boolean mirroring;
    private boolean resumed;
    private String currentHost;
    private ClipboardSync clipboardSync;
    private boolean localAdbBusy;
    // Other phones mirroring this one (needs the app on both phones).
    private IncomingSessions incomingSessions;
    private java.util.List<IncomingSessions.Session> incoming = java.util.Collections.emptyList();

    // Mirroring views.
    private LinearLayout mirrorContainer;
    private FrameLayout videoFrame;
    private SurfaceView surfaceView;
    private LinearLayout navBar;
    private TextView statusText;
    private int videoWidth;
    private int videoHeight;

    private SensorManager sensorManager;
    private boolean proximityRegistered;
    private boolean proximityInitialEventSeen;
    private long lastBackPress;
    private Object backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LogActivity.installCrashRecorder(this);
        boolean darkMode = getPreferences().getBoolean(PREFERENCE_DARK_MODE, false);
        setTheme(darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        clipboardSync = new ClipboardSync(this, () -> client);
        incomingSessions = new IncomingSessions(sessions -> {
            incoming = sessions;
            renderIncomingSessions();
            syncIncomingNotification(sessions);
        });
        // Stay on the settings page when the activity is recreated (e.g. after switching dark mode).
        settingsOpen = savedInstanceState != null && savedInstanceState.getBoolean(STATE_SETTINGS_OPEN, false);
        showSettings();
        SessionState.addListener(this);
        ShareService.addListener(shareListener);
        RunningNotification.setStopHandler(stopFromNotification);
        if (savedInstanceState == null && !handleShareLink(getIntent())) {
            runStartupPrompts(); // (opened from a share link: the start-up questions wait)
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareLink(intent);
    }

    /**
     * Opened from a share link (phoneshare://connect?host=…&pin=… from the link's web page, or the
     * https link itself): fills in the address and asks "Connect to this phone?". Returns true
     * when the intent was a link (handled, even if invalid).
     */
    private boolean handleShareLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return false;
        }
        String link = intent.getDataString();
        intent.setData(null); // handle it once (e.g. not again after a recreate)
        final String code = ShareLink.codeFrom(link);
        DiagnosticLog.i("app", "Opened from a share link: " + (code == null ? "invalid" : "ok"));
        if (code == null) {
            Toast.makeText(this, R.string.link_invalid, Toast.LENGTH_LONG).show();
            return true;
        }
        if (mirroring || launcher != null || isSessionActive()) {
            Toast.makeText(this, R.string.link_busy, Toast.LENGTH_LONG).show();
            return true;
        }
        if (!isCloudflareEdition()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.link_connect_title)
                    .setMessage(R.string.error_tunnel_needs_cloudflared)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        if (settingsOpen) {
            setSettingsPage(false);
        }
        EditText host = findViewById(R.id.editText_server_host);
        if (host != null) {
            host.setText(code);
        }
        getPreferences().edit().putString("Server Address", code).apply();
        String name = code.substring(0, code.indexOf('#'));
        new AlertDialog.Builder(this)
                .setTitle(R.string.link_connect_title)
                .setMessage(getString(R.string.link_connect_message, name))
                .setPositiveButton(R.string.link_connect_yes, (dialog, which) -> {
                    Button start = findViewById(R.id.button_start);
                    if (start != null && !mirroring && launcher == null && !isSessionActive()) {
                        onStartClicked(start);
                    }
                })
                .setNegativeButton(R.string.link_connect_no, null)
                .show();
        return true;
    }

    /** Stop in the "Phone Share is running" notification, for a session shown by this activity. */
    private final Runnable stopFromNotification = () -> {
        if (launcher != null) {
            launcher.cancel();
            launcher = null;
            SessionState.disconnected(null);
            restoreStartButton();
        }
        if (mirroring) {
            stopMirroring(null);
        }
    };

    private boolean isCloudflareEdition() {
        return getResources().getBoolean(R.bool.cloudflare_edition);
    }

    // ---------------------------------------------------------------- start-up questions

    /**
     * One dialog at a time: 1. the floating-window permission, 2. running in the background
     * (no battery restrictions), 3. the ADB question.
     */
    private void runStartupPrompts() {
        if (!overlayPromptChecked && needsOverlayPermission()) {
            overlayPromptChecked = true;
            showOverlayPrompt();
        } else {
            checkBatteryOnStartup();
        }
    }

    private boolean needsOverlayPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this);
    }

    /** "Display over other apps" is needed for the floating window: Go to settings, or Later. */
    private void showOverlayPrompt() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_prompt_title)
                .setMessage(R.string.overlay_prompt_message)
                .setPositiveButton(R.string.overlay_prompt_go, (dialog, which) -> {
                    DiagnosticLog.i("app", "Start-up: opening the floating window permission settings");
                    resumeStartupPrompts = true;
                    openOverlaySettings();
                })
                .setNegativeButton(R.string.overlay_prompt_later, (dialog, which) -> {
                    DiagnosticLog.i("app", "Start-up: floating window permission later");
                    checkBatteryOnStartup();
                })
                .setOnCancelListener(dialog -> checkBatteryOnStartup())
                .show();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (RuntimeException e2) {
                Toast.makeText(this, R.string.auto_float_needs_permission, Toast.LENGTH_LONG).show();
                resumeStartupPrompts = false;
                checkBatteryOnStartup();
            }
        }
    }

    /** When ADB over Wi-Fi is off: offer to turn it on, or just use the app as a controller. */
    // ---- 2. "Run in the background": no battery restrictions (Android 6+)

    private boolean needsBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return power != null && !power.isIgnoringBatteryOptimizations(getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Without this Android may pause the floating window, the "connected" notification and
     * Cloudflare sharing when the screen is off: Allow (one system dialog), or Later.
     */
    private void checkBatteryOnStartup() {
        if (batteryPromptChecked || !needsBatteryExemption()) {
            checkAdbOnStartup();
            return;
        }
        batteryPromptChecked = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_prompt_title)
                .setMessage(R.string.battery_prompt_message)
                .setPositiveButton(R.string.battery_prompt_allow, (dialog, which) -> {
                    DiagnosticLog.i("app", "Start-up: asking to run in the background");
                    resumeAfterBatteryPrompt = true;
                    if (!openBatteryExemption()) {
                        resumeAfterBatteryPrompt = false;
                        checkAdbOnStartup();
                    }
                })
                .setNegativeButton(R.string.overlay_prompt_later, (dialog, which) -> {
                    DiagnosticLog.i("app", "Start-up: background permission later");
                    checkAdbOnStartup();
                })
                .setOnCancelListener(dialog -> checkAdbOnStartup())
                .show();
    }

    /** The system "Allow Phone Share to always run in the background?" dialog, or the battery list. */
    @SuppressLint("BatteryLife")
    private boolean openBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
            return true;
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return true;
            } catch (RuntimeException e2) {
                Toast.makeText(this, R.string.battery_prompt_unavailable, Toast.LENGTH_LONG).show();
                return false;
            }
        }
    }

    // ---- 3. ADB over Wi-Fi

    private void checkAdbOnStartup() {
        if (adbPromptChecked || getPreferences().getBoolean(PREFERENCE_ADB_PROMPT_OFF, false)) {
            return;
        }
        adbPromptChecked = true;
        new Thread(() -> {
            final boolean listening = LocalAdb.isListening();
            runOnUiThread(() -> {
                if (!listening && !mirroring && launcher == null && !isFinishing() && !isDestroyed()) {
                    showAdbPrompt();
                }
            });
        }, "adb-startup-check").start();
    }

    private void showAdbPrompt() {
        final CheckBox dontAsk = new CheckBox(this);
        dontAsk.setText(R.string.adb_prompt_dont_ask);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(padding, padding / 3, padding, 0);
        box.addView(dontAsk);
        new AlertDialog.Builder(this)
                .setTitle(R.string.adb_prompt_title)
                .setMessage(R.string.adb_prompt_message)
                .setView(box)
                .setPositiveButton(R.string.adb_prompt_enable, (dialog, which) -> {
                    rememberAdbPrompt(dontAsk.isChecked());
                    DiagnosticLog.i("app", "Start-up: user chose to enable ADB");
                    changeLocalAdb(true); // root: done; otherwise shows how to do it
                })
                .setNegativeButton(R.string.adb_prompt_skip, (dialog, which) -> {
                    rememberAdbPrompt(dontAsk.isChecked());
                    DiagnosticLog.i("app", "Start-up: user only wants to control another phone");
                })
                .setOnCancelListener(dialog -> rememberAdbPrompt(dontAsk.isChecked()))
                .show();
    }

    private void rememberAdbPrompt(boolean dontAskAgain) {
        if (dontAskAgain) {
            getPreferences().edit().putBoolean(PREFERENCE_ADB_PROMPT_OFF, true).apply();
        }
    }

    // ---------------------------------------------------------------- "Phone Share is running" notification

    /** Shows the notification while this activity runs a session, removes it otherwise. */
    private void updateRunningNotification() {
        boolean own = launcher != null || client != null;
        if (own && isSessionActive()) {
            String who = SessionState.describe(SessionState.getHost(), SessionState.getDeviceName());
            RunningNotification.show(this, getString(SessionState.getStatus() == SessionState.Status.CONNECTED
                    ? R.string.notification_connected : R.string.notification_connecting, who));
        } else {
            RunningNotification.cancel(this);
        }
    }

    /** Android 13+ needs the user's permission to show the notification. */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not ask for the notification permission", e);
            }
        }
    }

    private final ShareService.Listener shareListener = this::renderCloudShare;

    // ---------------------------------------------------------------- settings screen

    private void showSettings() {
        mirroring = false;
        setContentView(R.layout.activity_main);
        mirrorContainer = null;
        videoFrame = null;
        surfaceView = null;
        navBar = null;
        statusText = null;
        updateBackHandling();

        final Button startButton = findViewById(R.id.button_start);
        loadSavedPreferences();
        startButton.setOnClickListener(view -> onStartClicked(startButton));
        findViewById(R.id.button_recommended).setOnClickListener(view -> restoreRecommendedSettings());
        findViewById(R.id.button_open_settings).setOnClickListener(view -> setSettingsPage(true));
        findViewById(R.id.button_close_settings).setOnClickListener(view -> setSettingsPage(false));
        findViewById(R.id.button_clear_host).setOnClickListener(view -> {
            EditText host = findViewById(R.id.editText_server_host);
            host.setText("");
            host.requestFocus();
            getPreferences().edit().putString("Server Address", "").apply();
            Toast.makeText(this, R.string.address_cleared, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.button_copy_adb_key).setOnClickListener(view -> showAdbKeyDialog());
        findViewById(R.id.button_connection_log).setOnClickListener(view -> openLog(LogActivity.SECTION_CONNECTION_LOG));
        findViewById(R.id.button_app_log).setOnClickListener(view -> openLog(LogActivity.SECTION_APP_LOG));
        new OptionsPanel(this, getPreferences(), findViewById(R.id.options_container)).build();
        // Both option boxes start collapsed; tapping a title shows or hides its options.
        setupCollapsible(R.id.header_normal, R.id.body_normal, R.string.section_scrcpy);
        setupCollapsible(R.id.header_advanced, R.id.options_container, R.string.options_more_title);
        setupLocalAdbCard();
        applySettingsPage(false);
        View cloudCard = findViewById(R.id.card_cloud_share);
        if (isCloudflareEdition()) {
            setupCloudShareCard();
        } else if (cloudCard != null) {
            cloudCard.setVisibility(View.GONE); // standard edition: no Cloudflare
        }
        updateSessionStatusView();
        renderIncomingSessions();
    }

    // ---------------------------------------------------------------- settings page

    /** Gear button / back arrow: switch between the main page and the settings page. */
    private void setSettingsPage(boolean open) {
        if (mirroring || settingsOpen == open) {
            return;
        }
        settingsOpen = open;
        saveHostField();
        applySettingsPage(true);
        updateBackHandling();
    }

    private void applySettingsPage(boolean scrollToTop) {
        View main = findViewById(R.id.page_main);
        View settings = findViewById(R.id.page_settings);
        View header = findViewById(R.id.header_bar);
        if (main == null || settings == null || header == null) {
            return;
        }
        main.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
        header.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
        settings.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
        if (settingsOpen) {
            refreshLocalAdb();
        }
        if (scrollToTop) {
            View scroll = findViewById(R.id.main_scroll);
            if (scroll != null) {
                scroll.scrollTo(0, 0);
            }
        }
    }

    // ---------------------------------------------------------------- share over the internet

    private void setupCloudShareCard() {
        Button toggle = findViewById(R.id.button_cloud_share);
        if (toggle == null) {
            return;
        }
        toggle.setOnClickListener(view -> {
            ShareService.Phase phase = ShareService.phase();
            boolean active = phase == ShareService.Phase.STARTING || phase == ShareService.Phase.WAITING
                    || phase == ShareService.Phase.ONLINE;
            if (!active) {
                requestNotificationPermission();
            }
            Intent intent = new Intent(this, ShareService.class)
                    .setAction(active ? ShareService.ACTION_STOP : ShareService.ACTION_START);
            try {
                if (!active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } catch (RuntimeException e) {
                Toast.makeText(this, getString(R.string.share_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.button_cloud_share_copy).setOnClickListener(view -> {
            String code = ShareService.shareCode();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (code != null && clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("scrcpy share code", code));
                Toast.makeText(this, R.string.share_copied, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.button_cloud_share_send).setOnClickListener(view -> {
            String code = ShareService.shareCode();
            if (code == null) {
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, code); // just the link
            try {
                startActivity(Intent.createChooser(send, getString(R.string.share_send)));
            } catch (RuntimeException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        renderCloudShare();
    }

    private void renderCloudShare() {
        Button toggle = mirroring ? null : (Button) findViewById(R.id.button_cloud_share);
        if (toggle == null) {
            return;
        }
        TextView status = findViewById(R.id.text_cloud_share_status);
        TextView code = findViewById(R.id.text_cloud_share_code);
        ShareService.Phase phase = ShareService.phase();
        boolean active = phase == ShareService.Phase.STARTING || phase == ShareService.Phase.WAITING
                || phase == ShareService.Phase.ONLINE;
        toggle.setText(active ? R.string.share_stop : R.string.share_start);
        toggle.setBackgroundResource(active ? R.drawable.bg_disconnect_button : R.drawable.bg_start_button);
        String message = ShareService.message();
        status.setVisibility(message == null ? View.GONE : View.VISIBLE);
        status.setText(message == null ? "" : message);
        String shareCode = ShareService.shareCode();
        boolean showCode = phase == ShareService.Phase.ONLINE && shareCode != null;
        code.setVisibility(showCode ? View.VISIBLE : View.GONE);
        code.setText(showCode ? shareCode : "");
        findViewById(R.id.button_cloud_share_copy).setEnabled(showCode);
        findViewById(R.id.button_cloud_share_send).setEnabled(showCode);
    }

    // ---------------------------------------------------------------- incoming sessions

    private void renderIncomingSessions() {
        LinearLayout container = mirroring ? null : (LinearLayout) findViewById(R.id.incoming_container);
        if (container == null) {
            return;
        }
        container.removeAllViews();
        container.setVisibility(incoming.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        java.text.DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(this);
        for (final IncomingSessions.Session session : incoming) {
            View item = inflater.inflate(R.layout.item_incoming_session, container, false);
            final String name = session.clientName.isEmpty() ? getString(R.string.incoming_unknown_name) : session.clientName;
            String ip = session.clientIp.isEmpty() ? "?" : session.clientIp;
            if ("127.0.0.1".equals(ip)) {
                ip = getString(R.string.incoming_via_cloudflare); // came in through ShareService
            }
            String since = session.startMillis > 0 ? timeFormat.format(new java.util.Date(session.startMillis)) : "?";
            ((TextView) item.findViewById(R.id.incoming_title)).setText(getString(R.string.incoming_title, name));
            ((TextView) item.findViewById(R.id.incoming_detail)).setText(getString(R.string.incoming_detail, ip, since));
            final Button button = item.findViewById(R.id.incoming_disconnect);
            button.setOnClickListener(view -> {
                button.setEnabled(false);
                button.setText(R.string.incoming_disconnecting);
                incomingSessions.disconnect(session, () -> {
                    if (!isFinishing()) {
                        Toast.makeText(this, getString(R.string.toast_incoming_disconnected, name), Toast.LENGTH_SHORT).show();
                    }
                });
            });
            container.addView(item);
        }
    }

    /**
     * The app is open, so the "connected to this phone" service may start now if Android did not
     * allow it in the background; a notification left over from a killed session is removed.
     */
    private void syncIncomingNotification(java.util.List<IncomingSessions.Session> sessions) {
        if (sessions.isEmpty()) {
            if (!IncomingSessionService.isRunning()) {
                IncomingNotifier.cancel(this);
            }
            return;
        }
        if (!IncomingSessionService.refresh(null) && resumed) {
            requestNotificationPermission();
            if (!IncomingSessionService.start(this, sessions.get(0))) {
                IncomingNotifier.show(this, sessions);
            }
        }
    }

    private void setupCollapsible(int headerId, int bodyId, int titleRes) {
        final TextView header = findViewById(headerId);
        final View body = findViewById(bodyId);
        if (header == null || body == null) {
            return;
        }
        body.setVisibility(View.GONE);
        final String title = getString(titleRes);
        header.setText("▸  " + title);
        header.setOnClickListener(view -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            header.setText((open ? "▾  " : "▸  ") + title);
        });
    }

    // ---------------------------------------------------------------- connection status

    @Override
    public void onSessionStateChanged(SessionState.Status status, String host, String deviceName, String message) {
        updateSessionStatusView();
        updateRunningNotification();
    }

    private void updateSessionStatusView() {
        TextView view = findViewById(R.id.text_session_status);
        if (view == null) {
            return;
        }
        String who = SessionState.describe(SessionState.getHost(), SessionState.getDeviceName());
        int color;
        switch (SessionState.getStatus()) {
            case CONNECTING:
                view.setText(getString(R.string.status_connecting, who));
                color = 0xFFF59E0B;
                break;
            case CONNECTED:
                view.setText(getString(R.string.status_connected, who));
                color = 0xFF16A34A;
                break;
            case DISCONNECTED: {
                String reason = SessionState.getMessage();
                view.setText(reason == null || reason.isEmpty() ? getString(R.string.status_disconnected)
                        : getString(R.string.status_disconnected_reason, shorten(reason, 90)));
                color = 0xFFDC2626;
                break;
            }
            case IDLE:
            default:
                view.setText(R.string.status_disconnected);
                color = 0xFF94A3B8;
                break;
        }
        // Color only the dot.
        CharSequence text = view.getText();
        android.text.SpannableString spannable = new android.text.SpannableString(text);
        if (text.length() > 0) {
            spannable.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(spannable);
        updateConnectButton();
    }

    /** The top button says Disconnect while a session (e.g. the floating window) is active. */
    private void updateConnectButton() {
        Button button = findViewById(R.id.button_start);
        if (button == null || launcher != null) {
            return; // our own connection attempt shows "Connecting…"
        }
        boolean active = isSessionActive();
        button.setEnabled(true);
        button.setText(active ? R.string.action_disconnect : R.string.action_start);
        button.setBackgroundResource(active ? R.drawable.bg_disconnect_button : R.drawable.bg_start_button);
    }

    private static boolean isSessionActive() {
        SessionState.Status status = SessionState.getStatus();
        return status == SessionState.Status.CONNECTED || status == SessionState.Status.CONNECTING;
    }

    /** Ends the session running in the floating window. */
    private void disconnectSession() {
        try {
            stopService(new Intent(this, FloatService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not stop the floating window", e);
        }
        SessionHandoff.Item pending = SessionHandoff.take();
        if (pending != null) {
            pending.client.stop();
        }
        SessionState.disconnected(null);
        Toast.makeText(this, R.string.toast_disconnected, Toast.LENGTH_SHORT).show();
    }

    private static String shorten(String text, int max) {
        String single = text.replace('\n', ' ').trim();
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------- ADB on this device

    private void setupLocalAdbCard() {
        Switch persist = findViewById(R.id.switch_local_adb_persist);
        persist.setChecked(getPreferences().getBoolean(PREFERENCE_LOCAL_ADB_PERSIST, false));
        bindSwitch(persist, PREFERENCE_LOCAL_ADB_PERSIST);
        findViewById(R.id.button_local_adb_on).setOnClickListener(view -> {
            requestNotificationPermission(); // for "<device> is connected to this phone"
            changeLocalAdb(true);
        });
        findViewById(R.id.button_local_adb_off).setOnClickListener(view -> changeLocalAdb(false));
        findViewById(R.id.button_local_adb_check).setOnClickListener(view -> refreshLocalAdb());
        findViewById(R.id.button_root_setup).setOnClickListener(view -> runRootSetup());
        refreshLocalAdb();
    }

    private void refreshLocalAdb() {
        TextView status = findViewById(R.id.text_local_adb_status);
        if (status == null || localAdbBusy) {
            return;
        }
        status.setText(R.string.local_adb_checking);
        new Thread(() -> {
            final boolean listening = LocalAdb.isListening();
            final java.util.List<String> addresses = LocalAdb.localAddresses();
            runOnUiThread(() -> {
                showLocalAdbStatus(listening, addresses);
                renderRootSetupStatus(listening);
            });
        }, "local-adb-check").start();
    }

    private void showLocalAdbStatus(boolean listening, java.util.List<String> addresses) {
        TextView status = findViewById(R.id.text_local_adb_status);
        if (status == null || isFinishing()) {
            return;
        }
        if (!listening) {
            status.setText(R.string.local_adb_off);
        } else if (addresses.isEmpty()) {
            status.setText(R.string.local_adb_on_no_ip);
        } else {
            StringBuilder list = new StringBuilder();
            for (String address : addresses) {
                if (list.length() > 0) {
                    list.append('\n');
                }
                int space = address.indexOf(' ');
                String ip = space < 0 ? address : address.substring(0, space);
                list.append("    ").append(ip).append(':').append(LocalAdb.PORT)
                        .append(space < 0 ? "" : address.substring(space));
            }
            status.setText(getString(R.string.local_adb_on, list));
        }
    }

    private void changeLocalAdb(boolean enable) {
        if (localAdbBusy) {
            return;
        }
        TextView status = findViewById(R.id.text_local_adb_status);
        localAdbBusy = true;
        setLocalAdbButtonsEnabled(false);
        if (status != null) {
            status.setText(enable ? R.string.local_adb_working : R.string.local_adb_working_off);
        }
        final boolean persist = isChecked(R.id.switch_local_adb_persist);
        new Thread(() -> {
            final LocalAdb.Result result = enable ? LocalAdb.enable(persist) : LocalAdb.disable();
            DiagnosticLog.i("local-adb", (enable ? "Enable" : "Disable") + " port " + LocalAdb.PORT + ": "
                    + (result.ok ? "ok" : result.output));
            runOnUiThread(() -> {
                localAdbBusy = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLocalAdbButtonsEnabled(true);
                if (result.ok) {
                    Toast.makeText(this, enable ? R.string.local_adb_enabled_toast : R.string.local_adb_disabled_toast,
                            Toast.LENGTH_SHORT).show();
                } else if (result.noRoot) {
                    showLocalAdbHelp(result.output, enable ? LocalAdb.enableCommand(persist) : LocalAdb.disableCommand());
                } else {
                    Toast.makeText(this, getString(R.string.local_adb_failed, result.output), Toast.LENGTH_LONG).show();
                }
                refreshLocalAdb();
            });
        }, "local-adb-change").start();
    }

    /** "Set up everything with root": ADB 5555 plus every permission the app can use, one root prompt. */
    private void runRootSetup() {
        if (localAdbBusy) {
            return;
        }
        localAdbBusy = true;
        setLocalAdbButtonsEnabled(false);
        TextView result = findViewById(R.id.text_root_setup_result);
        if (result != null) {
            result.setVisibility(View.VISIBLE);
            result.setText(R.string.root_setup_working);
        }
        final String packageName = getPackageName();
        new Thread(() -> {
            final LocalAdb.SetupResult setup = LocalAdb.rootSetup(packageName, Build.VERSION.SDK_INT);
            StringBuilder log = new StringBuilder("Root setup: ");
            for (int i = 0; i < setup.steps.size(); ++i) {
                log.append(setup.steps.get(i).label).append(setup.ok[i] ? " ok; " : " FAILED; ");
            }
            DiagnosticLog.i("local-adb", setup.result.noRoot ? "Root setup: " + setup.result.output : log.toString());
            runOnUiThread(() -> {
                localAdbBusy = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setLocalAdbButtonsEnabled(true);
                showRootSetupResult(setup);
                refreshLocalAdb();
            });
        }, "root-setup").start();
    }

    private void showRootSetupResult(LocalAdb.SetupResult setup) {
        if (setup.result.noRoot) {
            rootSetupError = getString(R.string.root_setup_no_root, setup.result.output);
            return; // refreshLocalAdb() (called next) shows it with the live status
        }
        rootSetupError = null;
        int done = 0;
        for (boolean ok : setup.ok) {
            if (ok) {
                done++;
            }
        }
        getPreferences().edit()
                .putLong(PREFERENCE_ROOT_SETUP_TIME, System.currentTimeMillis())
                .putInt(PREFERENCE_ROOT_SETUP_DONE, done)
                .putInt(PREFERENCE_ROOT_SETUP_TOTAL, setup.steps.size())
                .apply();
        Toast.makeText(this, getString(R.string.root_setup_done, done, setup.steps.size()), Toast.LENGTH_LONG).show();
    }

    /**
     * The root box shows what is really set up right now (checked live every time Settings is
     * shown), so after a force stop or reboot it says "Everything is set up" instead of asking
     * again. Only what Android lets an app read is checked.
     */
    private void renderRootSetupStatus(boolean adbListening) {
        TextView view = findViewById(R.id.text_root_setup_result);
        Button button = findViewById(R.id.button_root_setup);
        if (view == null || button == null) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        boolean all = true;
        all &= addCheck(lines, getString(R.string.root_check_adb_wifi), adbListening);
        all &= addCheck(lines, getString(R.string.root_check_dev_options),
                debugSetting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, adbListening));
        all &= addCheck(lines, getString(R.string.root_check_adb),
                debugSetting(Settings.Global.ADB_ENABLED, adbListening));
        if (Build.VERSION.SDK_INT >= 30) {
            all &= addCheck(lines, getString(R.string.root_check_remember), readGlobal("adb_allowed_connection_time", 0));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            all &= addCheck(lines, getString(R.string.root_check_overlay), Settings.canDrawOverlays(this));
            android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            all &= addCheck(lines, getString(R.string.root_check_battery),
                    power == null ? null : power.isIgnoringBatteryOptimizations(getPackageName()));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            all &= addCheck(lines, getString(R.string.root_check_notifications),
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        }

        StringBuilder text = new StringBuilder(getString(all ? R.string.root_status_all_done : R.string.root_status_missing));
        text.append("\n\n").append(lines.toString().trim());
        long last = getPreferences().getLong(PREFERENCE_ROOT_SETUP_TIME, 0);
        if (last > 0) {
            String when = android.text.format.DateFormat.getDateFormat(this).format(new java.util.Date(last)) + " "
                    + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(last));
            text.append("\n\n").append(getString(R.string.root_status_last_run,
                    getPreferences().getInt(PREFERENCE_ROOT_SETUP_DONE, 0),
                    getPreferences().getInt(PREFERENCE_ROOT_SETUP_TOTAL, 0), when));
        }
        if (rootSetupError != null) {
            text.append("\n\n").append(rootSetupError);
        }
        view.setVisibility(View.VISIBLE);
        view.setText(text);
        button.setText(all ? R.string.root_setup_button_again : R.string.root_setup_button);
    }

    /** Appends "✅ label" or "❌ label"; {@code ok == null} (cannot be checked) adds nothing. */
    private static boolean addCheck(StringBuilder lines, String label, Boolean ok) {
        if (ok == null) {
            return true;
        }
        lines.append(ok ? "✅ " : "❌ ").append(label).append('\n');
        return ok;
    }

    /**
     * Developer options / USB debugging. Android 17 hides both from apps (they always read 0,
     * see Settings.Global.ADB_ENABLED), and some phones did so earlier. ADB answering on port
     * 5555 proves debugging is on, so that wins; otherwise a 0 on Android 17+ means "unknown".
     */
    private Boolean debugSetting(String name, boolean adbListening) {
        if (adbListening) {
            return Boolean.TRUE;
        }
        Boolean value = readGlobal(name, 1);
        if (Build.VERSION.SDK_INT >= 37 && !Boolean.TRUE.equals(value)) {
            return null; // hidden by Android: cannot tell, so do not show a false ❌
        }
        return value;
    }

    /** Whether a Settings.Global integer equals {@code expected}; null when it cannot be read. */
    private Boolean readGlobal(String name, long expected) {
        try {
            String value = Settings.Global.getString(getContentResolver(), name);
            if (value == null) {
                return Boolean.FALSE; // never set: Android's default (off / 7-day expiry)
            }
            return Long.parseLong(value.trim()) == expected;
        } catch (RuntimeException e) {
            return null; // hidden setting not readable by apps on this Android version
        }
    }

    private void setLocalAdbButtonsEnabled(boolean enabled) {
        View on = findViewById(R.id.button_local_adb_on);
        View off = findViewById(R.id.button_local_adb_off);
        View rootSetup = findViewById(R.id.button_root_setup);
        if (on != null) {
            on.setEnabled(enabled);
            on.setAlpha(enabled ? 1f : 0.5f);
        }
        if (rootSetup != null) {
            rootSetup.setEnabled(enabled);
            rootSetup.setAlpha(enabled ? 1f : 0.5f);
        }
        if (off != null) {
            off.setEnabled(enabled);
        }
    }

    /** No root: explain the one-time computer step, with shortcuts. */
    private void showLocalAdbHelp(String reason, String rootCommand) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.local_adb_no_root_title)
                .setMessage(getString(R.string.local_adb_no_root_message, reason))
                .setPositiveButton(R.string.local_adb_open_dev_options, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                    }
                })
                .setNeutralButton(R.string.local_adb_copy_command, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("adb", "adb tcpip " + LocalAdb.PORT));
                        Toast.makeText(this, "adb tcpip " + LocalAdb.PORT, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openLog(int section) {
        saveHostField();
        startActivity(new Intent(this, LogActivity.class).putExtra(LogActivity.EXTRA_SECTION, section));
    }

    /** Shows a root command that adds this phone's ADB key to the target's adb_keys. */
    private void showAdbKeyDialog() {
        new Thread(() -> {
            String key;
            try {
                // May generate the key pair the first time, which takes a moment.
                key = AdbLauncher.publicKeyLine(getApplicationContext());
            } catch (AdbLauncher.LaunchException e) {
                final String message = e.getMessage();
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
                return;
            }
            final String command = "su -c 'mkdir -p /data/misc/adb && echo \"" + key
                    + "\" >> /data/misc/adb/adb_keys && chown system:shell /data/misc/adb/adb_keys"
                    + " && chmod 640 /data/misc/adb/adb_keys; restorecon /data/misc/adb/adb_keys;"
                    + " setprop service.adb.tcp.port 5555; stop adbd; start adbd'";
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.adb_key_title)
                        .setMessage(getString(R.string.adb_key_message, command))
                        .setPositiveButton(R.string.adb_key_copy, (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("adb key command", command));
                                Toast.makeText(this, R.string.adb_key_copied, Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "scrcpy-adb-key").start();
    }

    private void onStartClicked(Button startButton) {
        if (launcher != null) {
            return; // already connecting
        }
        if (isSessionActive()) {
            disconnectSession();
            return;
        }
        if (!readSettings()) {
            return;
        }
        requestNotificationPermission();
        if (sessionOptions.floatingWindow) {
            showFloatingWindow();
            return;
        }

        startButton.setEnabled(false);
        startButton.setText(R.string.action_connecting);
        final AdbLauncher currentLauncher = new AdbLauncher();
        launcher = currentLauncher;
        final HostAddress address = hostAddress;
        SessionState.connecting(address.toString());
        final ScrcpySessionOptions options = sessionOptions.copy();
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
            runOnUiThread(() -> onLaunchFinished(currentLauncher, result, message, address, options));
        }, "scrcpy-adb-connect");
        thread.start();
    }

    private void onLaunchFinished(AdbLauncher finishedLauncher, AdbLauncher.Session session, String error,
                                  HostAddress address, ScrcpySessionOptions options) {
        if (launcher != finishedLauncher || isFinishing() || isDestroyed()) {
            // Cancelled or the activity went away meanwhile.
            if (session != null) {
                session.closeAsync();
            }
            return;
        }
        launcher = null;
        if (session == null) {
            restoreStartButton();
            DiagnosticLog.i("app", "Connection failed: " + error);
            SessionState.disconnected(error == null ? "Connection failed" : error);
            Toast.makeText(this, (error == null ? "Connection failed" : error)
                    + "\n(See Logs › Connection log for details)", Toast.LENGTH_LONG).show();
            return;
        }
        startMirroring(session, address, options);
    }

    private void restoreStartButton() {
        Button startButton = findViewById(R.id.button_start);
        if (startButton != null) {
            startButton.setEnabled(true);
            startButton.setText(R.string.action_start);
        }
        updateConnectButton();
    }

    private void showFloatingWindow() {
        if (needsOverlayPermission()) {
            openOverlaySettings();
            Toast.makeText(this, "Allow display over other apps, then tap Connect again", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, FloatService.class);
        intent.setAction(FloatService.ACTION_START);
        intent.putExtra(FloatService.EXTRA_HOST, hostAddress.toString());
        sessionOptions.putInto(intent);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start the floating window", e);
            Toast.makeText(this, "Could not start the floating window: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        finish();
    }

    private void loadSavedPreferences() {
        SharedPreferences preferences = getPreferences();
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        final Switch darkModeSwitch = findViewById(R.id.switch_dark_mode);
        final Switch floatingWindowSwitch = findViewById(R.id.switch_floating_mode);
        final Switch noControlSwitch = findViewById(R.id.switch0);
        final Switch navSwitch = findViewById(R.id.switch1);
        final Switch audioSwitch = findViewById(R.id.switch_audio);
        final Switch powerOnSwitch = findViewById(R.id.switch_power_on);
        final Switch turnScreenOffSwitch = findViewById(R.id.switch_turn_screen_off);
        final Switch powerOffOnCloseSwitch = findViewById(R.id.switch_power_off_on_close);
        final Switch stayAwakeSwitch = findViewById(R.id.switch_stay_awake);
        final Switch showTouchesSwitch = findViewById(R.id.switch_show_touches);
        final Switch audioDupSwitch = findViewById(R.id.switch_audio_dup);
        final Switch keepActiveSwitch = findViewById(R.id.switch_keep_active);
        final Switch autoFloatSwitch = findViewById(R.id.switch_auto_float);
        migrateMovedOptions(preferences);

        editTextServerHost.setText(preferences.getString("Server Address", ""));
        darkModeSwitch.setChecked(preferences.getBoolean(PREFERENCE_DARK_MODE, false));
        floatingWindowSwitch.setChecked(preferences.getBoolean(PREFERENCE_FLOATING_WINDOW, false));
        noControlSwitch.setChecked(preferences.getBoolean("No Control", false));
        navSwitch.setChecked(preferences.getBoolean("Nav Switch", false));
        audioSwitch.setChecked(preferences.getBoolean("Audio", DEFAULT_AUDIO));
        powerOnSwitch.setChecked(preferences.getBoolean("Power On", true));
        turnScreenOffSwitch.setChecked(preferences.getBoolean("Turn Screen Off", false));
        powerOffOnCloseSwitch.setChecked(preferences.getBoolean("Power Off On Close", false));
        stayAwakeSwitch.setChecked(preferences.getBoolean("Stay Awake", false));
        showTouchesSwitch.setChecked(preferences.getBoolean("Show Touches", false));
        audioDupSwitch.setChecked(preferences.getBoolean(PREFERENCE_AUDIO_DUP, DEFAULT_AUDIO_DUP));
        keepActiveSwitch.setChecked(preferences.getBoolean(PREFERENCE_KEEP_ACTIVE, false));
        autoFloatSwitch.setChecked(preferences.getBoolean(PREFERENCE_AUTO_FLOAT, true));
        // Remember the address as it is typed, not only when connecting.
        editTextServerHost.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                getPreferences().edit().putString("Server Address", s.toString().trim()).apply();
            }
        });

        // Labels and values have the same order; new installs start at 480p (recommended).
        setSpinner(R.array.options_resolution_labels, R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION,
                RESOLUTION_480P);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE,
                DEFAULT_BITRATE_INDEX);
        setSpinner(R.array.options_fps_values, R.id.spinner_video_fps, PREFERENCE_SPINNER_FPS, DEFAULT_FPS_INDEX);

        applyNoControlState(noControlSwitch.isChecked(), navSwitch, powerOnSwitch, turnScreenOffSwitch);
        noControlSwitch.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean("No Control", checked).apply();
            applyNoControlState(checked, navSwitch, powerOnSwitch, turnScreenOffSwitch);
            if (checked) {
                turnScreenOffSwitch.setChecked(false);
            }
        });
        bindSwitch(navSwitch, "Nav Switch");
        audioSwitch.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean("Audio", checked).apply();
            if (!checked) {
                audioDupSwitch.setChecked(false); // nothing to duplicate without audio
            }
        });
        audioDupSwitch.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean(PREFERENCE_AUDIO_DUP, checked).apply();
            if (checked) {
                audioSwitch.setChecked(true);
            }
        });
        bindSwitch(keepActiveSwitch, PREFERENCE_KEEP_ACTIVE);
        bindSwitch(autoFloatSwitch, PREFERENCE_AUTO_FLOAT);
        bindSwitch(powerOnSwitch, "Power On");
        bindSwitch(turnScreenOffSwitch, "Turn Screen Off");
        bindSwitch(powerOffOnCloseSwitch, "Power Off On Close");
        bindSwitch(stayAwakeSwitch, "Stay Awake");
        bindSwitch(showTouchesSwitch, "Show Touches");
        bindSwitch(floatingWindowSwitch, PREFERENCE_FLOATING_WINDOW);
        darkModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            saveHostField();
            getPreferences().edit().putBoolean(PREFERENCE_DARK_MODE, checked).apply();
            recreate();
        });
    }

    /** audio_dup and keep_active moved from the Advanced box to Normal: keep earlier choices. */
    private static void migrateMovedOptions(SharedPreferences preferences) {
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        String[][] moved = {{"adv_audio_dup", PREFERENCE_AUDIO_DUP}, {"adv_keep_active", PREFERENCE_KEEP_ACTIVE}};
        for (String[] entry : moved) {
            if (preferences.contains(entry[0])) {
                if (!preferences.contains(entry[1])) {
                    editor.putBoolean(entry[1], Boolean.parseBoolean(preferences.getString(entry[0], "false")));
                }
                editor.remove(entry[0]);
                changed = true;
            }
        }
        // Keep the chosen video size now that 480p sits at index 5 (later entries moved down one).
        if (preferences.contains(PREFERENCE_SPINNER_RESOLUTION_OLD)) {
            if (!preferences.contains(PREFERENCE_SPINNER_RESOLUTION)) {
                int old = preferences.getInt(PREFERENCE_SPINNER_RESOLUTION_OLD, 2);
                editor.putInt(PREFERENCE_SPINNER_RESOLUTION, old >= RESOLUTION_480P ? old + 1 : old);
            }
            editor.remove(PREFERENCE_SPINNER_RESOLUTION_OLD);
            changed = true;
        }
        // New recommended defaults: applied once when updating from an older version. Anything
        // the user changes afterwards is remembered as before.
        if (preferences.getInt(PREFERENCE_DEFAULTS_VERSION, 0) < DEFAULTS_VERSION) {
            putRecommendedSettings(editor);
            editor.putInt(PREFERENCE_DEFAULTS_VERSION, DEFAULTS_VERSION);
            changed = true;
        }
        if (changed) {
            editor.apply();
        }
    }

    /** 480p, 1 Mbps, 30 fps, H.264, audio on, audio on both devices. */
    private static void putRecommendedSettings(SharedPreferences.Editor editor) {
        editor.putInt(PREFERENCE_SPINNER_RESOLUTION, RESOLUTION_480P)
                .putInt(PREFERENCE_SPINNER_BITRATE, DEFAULT_BITRATE_INDEX)
                .putInt(PREFERENCE_SPINNER_FPS, DEFAULT_FPS_INDEX)
                .putBoolean("Audio", DEFAULT_AUDIO)
                .putBoolean(PREFERENCE_AUDIO_DUP, DEFAULT_AUDIO_DUP)
                .putString(AdvancedOptions.PREF_PREFIX + AdvancedOptions.KEY_VIDEO_CODEC, "h264");
    }

    private void restoreRecommendedSettings() {
        SharedPreferences.Editor editor = getPreferences().edit();
        putRecommendedSettings(editor);
        editor.apply();
        showSettings(); // reload every control from the saved settings
        View body = findViewById(R.id.body_normal);
        if (body != null && body.getVisibility() != View.VISIBLE) {
            findViewById(R.id.header_normal).performClick(); // keep the Normal box open
        }
        Toast.makeText(this, R.string.recommended_restored, Toast.LENGTH_SHORT).show();
    }

    private static void applyNoControlState(boolean noControl, Switch navSwitch, Switch powerOnSwitch,
                                            Switch turnScreenOffSwitch) {
        navSwitch.setVisibility(noControl ? View.GONE : View.VISIBLE);
        powerOnSwitch.setEnabled(!noControl);
        turnScreenOffSwitch.setEnabled(!noControl);
    }

    private void bindSwitch(Switch view, String key) {
        view.setOnCheckedChangeListener((button, checked) -> getPreferences().edit().putBoolean(key, checked).apply());
    }

    private void setSpinner(int textArrayResource, int spinnerId, String preferenceId, int defaultPosition) {
        Spinner spinner = findViewById(spinnerId);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, textArrayResource,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int position = getPreferences().getInt(preferenceId, defaultPosition);
        if (position < 0 || position >= adapter.getCount()) {
            position = Math.min(defaultPosition, adapter.getCount() - 1);
        }
        spinner.setSelection(position);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int selected, long id) {
                getPreferences().edit().putInt(preferenceId, selected).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the stored value.
            }
        });
    }

    private void saveHostField() {
        EditText editTextServerHost = findViewById(R.id.editText_server_host);
        if (editTextServerHost != null) {
            getPreferences().edit().putString("Server Address", editTextServerHost.getText().toString().trim()).apply();
        }
    }

    /** Reads and validates the settings screen. Returns false (after telling the user) when invalid. */
    private boolean readSettings() {
        EditText editTextServerHost = findViewById(R.id.editText_server_host);
        String serverText = editTextServerHost.getText().toString().trim();
        getPreferences().edit().putString("Server Address", serverText).apply();
        if (serverText.isEmpty()) {
            Toast.makeText(this, "Enter the target device IP address", Toast.LENGTH_SHORT).show();
            return false;
        }
        HostAddress address = HostAddress.parse(serverText);
        if (address == null) {
            Toast.makeText(this, R.string.error_invalid_address, Toast.LENGTH_LONG).show();
            return false;
        }
        if (address.isTunnel() && !isCloudflareEdition()) {
            Toast.makeText(this, R.string.error_tunnel_needs_cloudflared, Toast.LENGTH_LONG).show();
            return false;
        }
        hostAddress = address;

        String[] resolutions = getResources().getStringArray(R.array.options_resolution_values);
        int[] bitrates = getResources().getIntArray(R.array.options_bitrate_values);
        String[] fpsValues = getResources().getStringArray(R.array.options_fps_values);
        String resolution = resolutions[selectedIndex(R.id.spinner_video_resolution, resolutions.length, RESOLUTION_480P)];
        int maxSize = 1280;
        try {
            String[] dimensions = resolution.split("x");
            maxSize = Math.max(Integer.parseInt(dimensions[0].trim()), Integer.parseInt(dimensions[1].trim()));
        } catch (RuntimeException e) {
            Log.w(TAG, "Invalid resolution entry: " + resolution, e);
        }
        int videoBitrate = bitrates[selectedIndex(R.id.spinner_video_bitrate, bitrates.length, DEFAULT_BITRATE_INDEX)];
        int maxFps = 60;
        try {
            maxFps = Integer.parseInt(fpsValues[selectedIndex(R.id.spinner_video_fps, fpsValues.length, DEFAULT_FPS_INDEX)].trim());
        } catch (RuntimeException e) {
            Log.w(TAG, "Invalid frame rate entry", e);
        }

        ScrcpySessionOptions options = new ScrcpySessionOptions();
        options.noControl = isChecked(R.id.switch0);
        options.navBar = isChecked(R.id.switch1) && !options.noControl;
        options.audioEnabled = isChecked(R.id.switch_audio);
        options.powerOn = isChecked(R.id.switch_power_on);
        options.turnScreenOffOnConnect = isChecked(R.id.switch_turn_screen_off) && !options.noControl;
        options.powerOffOnClose = isChecked(R.id.switch_power_off_on_close);
        options.stayAwake = isChecked(R.id.switch_stay_awake);
        options.showTouches = isChecked(R.id.switch_show_touches);
        options.floatingWindow = isChecked(R.id.switch_floating_mode);
        options.maxSize = maxSize;
        options.videoBitRate = videoBitrate;
        options.maxFps = maxFps;

        String error = AdvancedOptions.apply(OptionsPanel.storedValues(getPreferences()), options);
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            return false;
        }
        AdvancedOptions.applyNormal(options, isChecked(R.id.switch_audio_dup), isChecked(R.id.switch_keep_active));
        if (!com.lemonrsh.phoneshare.decoder.Codecs.hasDecoder(com.lemonrsh.phoneshare.decoder.Codecs.videoMime(options.videoCodec))) {
            Toast.makeText(this, getString(R.string.codec_unsupported,
                    com.lemonrsh.phoneshare.decoder.Codecs.describeId(com.lemonrsh.phoneshare.decoder.Codecs.videoId(options.videoCodec)),
                    "All other scrcpy options › Video"), Toast.LENGTH_LONG).show();
            return false;
        }
        int audioId = com.lemonrsh.phoneshare.decoder.Codecs.audioId(options.audioCodec);
        if (options.audioEnabled && !com.lemonrsh.phoneshare.decoder.Codecs.hasDecoder(
                com.lemonrsh.phoneshare.decoder.Codecs.audioMime(audioId))) {
            Toast.makeText(this, getString(R.string.codec_unsupported,
                    com.lemonrsh.phoneshare.decoder.Codecs.describeId(audioId), "All other scrcpy options › Audio"),
                    Toast.LENGTH_LONG).show();
            return false;
        }
        sessionOptions = options;
        return true;
    }

    private int selectedIndex(int spinnerId, int count, int fallback) {
        Spinner spinner = findViewById(spinnerId);
        int position = spinner == null ? fallback : spinner.getSelectedItemPosition();
        if (position < 0 || position >= count) {
            position = Math.max(0, Math.min(fallback, count - 1));
        }
        return position;
    }

    private boolean isChecked(int switchId) {
        Switch view = findViewById(switchId);
        return view != null && view.isChecked();
    }

    private SharedPreferences getPreferences() {
        return getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- mirroring screen

    @SuppressLint("ClickableViewAccessibility")
    private void startMirroring(AdbLauncher.Session session, HostAddress address, ScrcpySessionOptions options) {
        mirroring = true;
        settingsOpen = false; // back to the main page when the session ends
        videoWidth = 0;
        videoHeight = 0;
        setContentView(R.layout.surface);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        mirrorContainer = findViewById(R.id.container1);
        videoFrame = findViewById(R.id.video_frame);
        surfaceView = findViewById(R.id.decoder_surface);
        navBar = findViewById(R.id.nav_button_bar);
        statusText = findViewById(R.id.status_text);

        currentHost = address.toString();
        client = new ScrcpyClient(address.host, options, session, this);
        surfaceView.getHolder().addCallback(this);
        if (surfaceView.getHolder().getSurface().isValid()) {
            client.setSurface(surfaceView.getHolder().getSurface());
        }

        boolean controls = !options.noControl;
        navBar.setVisibility(controls && options.navBar ? View.VISIBLE : View.GONE);
        if (controls) {
            surfaceView.setOnTouchListener((view, event) -> {
                ScrcpyClient current = client;
                return current != null && current.sendTouch(event, view.getWidth(), view.getHeight());
            });
            findViewById(R.id.back_button).setOnClickListener(view -> sendKey(4));
            findViewById(R.id.home_button).setOnClickListener(view -> sendKey(3));
            findViewById(R.id.appswitch_button).setOnClickListener(view -> sendKey(187));
        }
        videoFrame.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                view.post(this::fitSurfaceToVideo);
            }
        });
        applyMirrorLayout();
        updateBackHandling();
        if (resumed) {
            registerProximity();
        }
        client.start();
    }

    private void sendKey(int keycode) {
        if (client != null) {
            client.sendKeyEvent(keycode);
        }
    }

    /** Stops the current session (if any) and returns to the settings screen. */
    private void stopMirroring(String message) {
        unregisterProximity();
        clipboardSync.stop();
        ScrcpyClient current = client;
        client = null;
        boolean wasActive = SessionState.getStatus() == SessionState.Status.CONNECTED
                || SessionState.getStatus() == SessionState.Status.CONNECTING;
        if (current != null || wasActive) {
            SessionState.disconnected(message);
        }
        if (current != null) {
            current.stop();
        }
        if (surfaceView != null) {
            surfaceView.getHolder().removeCallback(this);
            surfaceView.setOnTouchListener(null);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        showSystemUi();
        showSettings();
        Toast.makeText(this, message == null ? getString(R.string.toast_disconnected)
                : getString(R.string.toast_disconnected_reason, message), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onConnected(int width, int height) {
        if (statusText != null) {
            statusText.setVisibility(View.GONE);
        }
        String name = client == null ? null : client.getDeviceName();
        SessionState.connected(currentHost, name);
        Toast.makeText(this, getString(R.string.toast_connected, SessionState.describe(currentHost, name)),
                Toast.LENGTH_SHORT).show();
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
    @SuppressLint("SourceLockedOrientationActivity")
    public void onVideoSizeChanged(int width, int height) {
        if (!mirroring) {
            return;
        }
        videoWidth = width;
        videoHeight = height;
        setRequestedOrientation(width > height ? ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        fitSurfaceToVideo();
    }

    @Override
    public void onDisconnected(String message) {
        if (mirroring) {
            stopMirroring(message);
        } else if (isSessionActive()) {
            // The session ended while being handed to the floating window.
            SessionState.disconnected(message);
        }
    }

    // ---------------------------------------------------------------- auto floating window

    /** Home, Recents or another app: continue the mirror in the floating window. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (mirroring && client != null && client.isConnected()
                && getPreferences().getBoolean(PREFERENCE_AUTO_FLOAT, true)) {
            moveSessionToFloatingWindow();
        }
    }

    private void moveSessionToFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.auto_float_needs_permission, Toast.LENGTH_LONG).show();
            return;
        }
        ScrcpyClient current = client;
        unregisterProximity();
        clipboardSync.stop();
        client = null;
        if (surfaceView != null) {
            surfaceView.getHolder().removeCallback(this);
            surfaceView.setOnTouchListener(null);
        }
        // This activity's Surface goes away now: stop rendering into it before handing over.
        current.setSurface(null);
        SessionHandoff.put(current, sessionOptions.copy(), currentHost);
        Intent intent = new Intent(this, FloatService.class).setAction(FloatService.ACTION_ADOPT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not move the session to the floating window", e);
            SessionHandoff.Item item = SessionHandoff.take();
            if (item != null) {
                item.client.stop();
            }
            SessionState.disconnected("Could not open the floating window");
        }
        DiagnosticLog.i("app", "Leaving the app: moving the mirror to the floating window");
        updateRunningNotification(); // the floating window shows its own notification
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        showSystemUi();
        showSettings();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mirroring) {
            applyMirrorLayout();
            hideSystemUi();
        }
    }

    /** Arranges the video area and navigation bar for the current orientation. */
    private void applyMirrorLayout() {
        if (mirrorContainer == null || videoFrame == null || navBar == null) {
            return;
        }
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int navSize = Math.round(64 * getResources().getDisplayMetrics().density);
        mirrorContainer.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        videoFrame.setLayoutParams(landscape
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        navBar.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        navBar.setLayoutParams(landscape
                ? new LinearLayout.LayoutParams(navSize, ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, navSize));
        for (int i = 0; i < navBar.getChildCount(); ++i) {
            navBar.getChildAt(i).setLayoutParams(landscape
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        videoFrame.post(this::fitSurfaceToVideo);
    }

    /** Sizes the SurfaceView to the video aspect ratio, centered in the available area. */
    private void fitSurfaceToVideo() {
        if (surfaceView == null || videoFrame == null) {
            return;
        }
        int frameWidth = videoFrame.getWidth();
        int frameHeight = videoFrame.getHeight();
        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ViewGroup.LayoutParams.MATCH_PARENT;
        if (frameWidth > 0 && frameHeight > 0 && videoWidth > 0 && videoHeight > 0) {
            float scale = Math.min((float) frameWidth / videoWidth, (float) frameHeight / videoHeight);
            width = Math.max(1, Math.round(videoWidth * scale));
            height = Math.max(1, Math.round(videoHeight * scale));
        }
        ViewGroup.LayoutParams current = surfaceView.getLayoutParams();
        if (current != null && current.width == width && current.height == height) {
            return;
        }
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @SuppressWarnings("deprecation")
    private void showSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ---------------------------------------------------------------- SurfaceHolder.Callback

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (client != null) {
            client.setSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The decoder scales into the Surface; nothing to do.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Must stop rendering into the Surface before returning.
        if (client != null) {
            client.setSurface(null);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        incomingSessions.start();
        if (settingsOpen && !mirroring) {
            refreshLocalAdb(); // e.g. back from Android settings: show what is set up now
        }
        if (resumeStartupPrompts) {
            resumeStartupPrompts = false;
            if (!needsOverlayPermission()) {
                Toast.makeText(this, R.string.overlay_prompt_granted, Toast.LENGTH_SHORT).show();
            }
            checkBatteryOnStartup();
        } else if (resumeAfterBatteryPrompt) {
            resumeAfterBatteryPrompt = false;
            if (!needsBatteryExemption()) {
                Toast.makeText(this, R.string.battery_prompt_granted, Toast.LENGTH_SHORT).show();
            }
            checkAdbOnStartup();
        }
        if (mirroring) {
            hideSystemUi();
            registerProximity();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        incomingSessions.stop();
        unregisterProximity();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mirroring) {
            hideSystemUi();
        }
    }

    @Override
    protected void onDestroy() {
        SessionState.removeListener(this);
        ShareService.removeListener(shareListener);
        RunningNotification.clearStopHandler(stopFromNotification);
        clipboardSync.stop();
        boolean hadSession = launcher != null || client != null;
        if (launcher != null) {
            launcher.cancel();
            launcher = null;
        }
        unregisterProximity();
        if (client != null) {
            client.stop();
            client = null;
        }
        if (hadSession) {
            SessionState.disconnected(null);
            RunningNotification.cancel(this);
        }
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            BackCallbacks.unregister(this, backCallback);
            backCallback = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- back handling

    private void updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        boolean handleBack = mirroring || settingsOpen;
        if (handleBack && backCallback == null) {
            backCallback = BackCallbacks.register(this, this::handleBack);
        } else if (!handleBack && backCallback != null) {
            BackCallbacks.unregister(this, backCallback);
            backCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mirroring || settingsOpen) {
            handleBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Back: leave the settings page, or (double press) stop mirroring. */
    private void handleBack() {
        if (mirroring) {
            handleMirrorBack();
        } else if (settingsOpen) {
            setSettingsPage(false);
        }
    }

    private void handleMirrorBack() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPress < BACK_PRESS_INTERVAL_MS) {
            lastBackPress = 0;
            stopMirroring(null);
        } else {
            lastBackPress = now;
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show();
        }
    }

    @TargetApi(33)
    private static final class BackCallbacks {
        static Object register(Activity activity, Runnable action) {
            OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(Activity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }

    // ---------------------------------------------------------------- proximity sensor

    private void registerProximity() {
        if (proximityRegistered || sensorManager == null || client == null || sessionOptions.noControl) {
            return;
        }
        Sensor proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximity != null) {
            proximityInitialEventSeen = false;
            proximityRegistered = sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void unregisterProximity() {
        if (proximityRegistered && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        proximityRegistered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PROXIMITY || client == null || !client.isConnected()) {
            return;
        }
        if (!proximityInitialEventSeen) {
            // The first event only reports the current state; do not override "turn screen off".
            proximityInitialEventSeen = true;
            return;
        }
        boolean near = event.values.length > 0 && event.values[0] < event.sensor.getMaximumRange();
        // Sent asynchronously by the client's control thread.
        client.setDisplayPower(!near);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed.
    }
}
