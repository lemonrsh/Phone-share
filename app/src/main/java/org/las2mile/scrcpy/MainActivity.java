package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";
    private static final String PREFERENCE_SPINNER_FPS = "spinner_fps";
    private static final String PREFERENCE_DARK_MODE = "dark_mode";
    private static final String PREFERENCE_FLOATING_WINDOW = "floating_window";
    private static final String PREFERENCE_LOCAL_ADB_PERSIST = "local_adb_persist";
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
        showSettings();
        SessionState.addListener(this);
    }

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
        findViewById(R.id.button_copy_adb_key).setOnClickListener(view -> showAdbKeyDialog());
        findViewById(R.id.button_connection_log).setOnClickListener(view -> openLog(LogActivity.SECTION_CONNECTION_LOG));
        findViewById(R.id.button_app_log).setOnClickListener(view -> openLog(LogActivity.SECTION_APP_LOG));
        new OptionsPanel(this, getPreferences(), findViewById(R.id.options_container)).build();
        setupLocalAdbCard();
        updateSessionStatusView();
    }

    // ---------------------------------------------------------------- connection status

    @Override
    public void onSessionStateChanged(SessionState.Status status, String host, String deviceName, String message) {
        updateSessionStatusView();
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
        findViewById(R.id.button_local_adb_on).setOnClickListener(view -> changeLocalAdb(true));
        findViewById(R.id.button_local_adb_off).setOnClickListener(view -> changeLocalAdb(false));
        findViewById(R.id.button_local_adb_check).setOnClickListener(view -> refreshLocalAdb());
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
            runOnUiThread(() -> showLocalAdbStatus(listening, addresses));
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

    private void setLocalAdbButtonsEnabled(boolean enabled) {
        View on = findViewById(R.id.button_local_adb_on);
        View off = findViewById(R.id.button_local_adb_off);
        if (on != null) {
            on.setEnabled(enabled);
            on.setAlpha(enabled ? 1f : 0.5f);
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
        if (!readSettings()) {
            return;
        }
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
    }

    private void showFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
            Toast.makeText(this, "Allow display over other apps, then tap Start again", Toast.LENGTH_LONG).show();
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

        editTextServerHost.setText(preferences.getString("Server Address", ""));
        darkModeSwitch.setChecked(preferences.getBoolean(PREFERENCE_DARK_MODE, false));
        floatingWindowSwitch.setChecked(preferences.getBoolean(PREFERENCE_FLOATING_WINDOW, false));
        noControlSwitch.setChecked(preferences.getBoolean("No Control", false));
        navSwitch.setChecked(preferences.getBoolean("Nav Switch", false));
        audioSwitch.setChecked(preferences.getBoolean("Audio", false));
        powerOnSwitch.setChecked(preferences.getBoolean("Power On", true));
        turnScreenOffSwitch.setChecked(preferences.getBoolean("Turn Screen Off", false));
        powerOffOnCloseSwitch.setChecked(preferences.getBoolean("Power Off On Close", false));
        stayAwakeSwitch.setChecked(preferences.getBoolean("Stay Awake", false));
        showTouchesSwitch.setChecked(preferences.getBoolean("Show Touches", false));

        setSpinner(R.array.options_resolution_values, R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION, 2);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE, 2);
        setSpinner(R.array.options_fps_values, R.id.spinner_video_fps, PREFERENCE_SPINNER_FPS, 1);

        applyNoControlState(noControlSwitch.isChecked(), navSwitch, powerOnSwitch, turnScreenOffSwitch);
        noControlSwitch.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean("No Control", checked).apply();
            applyNoControlState(checked, navSwitch, powerOnSwitch, turnScreenOffSwitch);
            if (checked) {
                turnScreenOffSwitch.setChecked(false);
            }
        });
        bindSwitch(navSwitch, "Nav Switch");
        bindSwitch(audioSwitch, "Audio");
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
            Toast.makeText(this, "Invalid address. Use an IP such as 192.168.1.42 or 192.168.1.42:5555",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        hostAddress = address;

        String[] resolutions = getResources().getStringArray(R.array.options_resolution_values);
        int[] bitrates = getResources().getIntArray(R.array.options_bitrate_values);
        String[] fpsValues = getResources().getStringArray(R.array.options_fps_values);
        String resolution = resolutions[selectedIndex(R.id.spinner_video_resolution, resolutions.length, 2)];
        int maxSize = 1280;
        try {
            String[] dimensions = resolution.split("x");
            maxSize = Math.max(Integer.parseInt(dimensions[0].trim()), Integer.parseInt(dimensions[1].trim()));
        } catch (RuntimeException e) {
            Log.w(TAG, "Invalid resolution entry: " + resolution, e);
        }
        int videoBitrate = bitrates[selectedIndex(R.id.spinner_video_bitrate, bitrates.length, 2)];
        int maxFps = 60;
        try {
            maxFps = Integer.parseInt(fpsValues[selectedIndex(R.id.spinner_video_fps, fpsValues.length, 1)].trim());
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
        if (!org.las2mile.scrcpy.decoder.Codecs.hasDecoder(org.las2mile.scrcpy.decoder.Codecs.videoMime(options.videoCodec))) {
            Toast.makeText(this, getString(R.string.codec_unsupported,
                    org.las2mile.scrcpy.decoder.Codecs.describeId(org.las2mile.scrcpy.decoder.Codecs.videoId(options.videoCodec)),
                    "All other scrcpy options › Video"), Toast.LENGTH_LONG).show();
            return false;
        }
        int audioId = org.las2mile.scrcpy.decoder.Codecs.audioId(options.audioCodec);
        if (options.audioEnabled && !org.las2mile.scrcpy.decoder.Codecs.hasDecoder(
                org.las2mile.scrcpy.decoder.Codecs.audioMime(audioId))) {
            Toast.makeText(this, getString(R.string.codec_unsupported,
                    org.las2mile.scrcpy.decoder.Codecs.describeId(audioId), "All other scrcpy options › Audio"),
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
        }
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
        if (mirroring) {
            hideSystemUi();
            registerProximity();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
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
        if (mirroring && backCallback == null) {
            backCallback = BackCallbacks.register(this, this::handleMirrorBack);
        } else if (!mirroring && backCallback != null) {
            BackCallbacks.unregister(this, backCallback);
            backCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mirroring) {
            handleMirrorBack();
        } else {
            super.onBackPressed();
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
