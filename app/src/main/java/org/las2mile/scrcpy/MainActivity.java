package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {
    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";
    private static final String PREFERENCE_SPINNER_FPS = "spinner_fps";
    private static final String PREFERENCE_DARK_MODE = "dark_mode";
    private static final String PREFERENCE_FLOATING_WINDOW = "floating_window";

    private static int screenWidth = 1080;
    private static int screenHeight = 1920;
    private static boolean landscape;
    private static boolean first_time = true;
    private static boolean result_of_Rotation;
    private static boolean serviceBound;
    private static boolean nav;
    private static boolean no_control;
    private static ScrcpySessionOptions activeSessionOptions;

    private SensorManager sensorManager;
    private SendCommands sendCommands;
    private int videoBitrate;
    private String local_ip;
    private Context context;
    private String serverAdr = "";
    private SurfaceView surfaceView;
    private Surface surface;
    private Scrcpy scrcpy;
    private long timestamp;
    private byte[] fileBase64;
    private float remote_device_width;
    private float remote_device_height;
    private LinearLayout linearLayout;
    private ScrcpySessionOptions sessionOptions = new ScrcpySessionOptions();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                scrcpy.start(surface, serverAdr, screenHeight, screenWidth);
                waitForConnection();
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                set_display_nd_touch();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean darkMode = getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE)
                .getBoolean(PREFERENCE_DARK_MODE, false);
        setTheme(darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(savedInstanceState);

        if (first_time) {
            scrcpy_main();
        } else {
            context = this;
            start_screen_copy_magic();
        }

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            Sensor proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (proximity != null) {
                sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    public void scrcpy_main() {
        setContentView(R.layout.activity_main);
        context = this;
        final Button startButton = findViewById(R.id.button_start);
        sendCommands = new SendCommands();
        loadServerAsset();
        get_saved_preferences();

        startButton.setOnClickListener(view -> {
            getAttributes();
            if (serverAdr.isEmpty()) {
                Toast.makeText(context, "Enter the target device IP address", Toast.LENGTH_SHORT).show();
                return;
            }

            if (sessionOptions.floatingWindow) {
                showDisplayWindow();
                return;
            }

            local_ip = wifiIpAddress();
            if (local_ip == null || local_ip.isEmpty()) {
                Toast.makeText(context, "Connect this device to Wi-Fi and try again", Toast.LENGTH_SHORT).show();
                return;
            }
            if (fileBase64 == null || fileBase64.length == 0) {
                Toast.makeText(context, "The scrcpy server could not be loaded", Toast.LENGTH_SHORT).show();
                return;
            }

            startButton.setEnabled(false);
            new Thread(() -> {
                int result = sendCommands.SendAdbCommands(context, fileBase64, serverAdr, local_ip, sessionOptions);
                runOnUiThread(() -> {
                    startButton.setEnabled(true);
                    if (result == 0) {
                        start_screen_copy_magic();
                    } else {
                        Toast.makeText(context, "Network or ADB connection failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }, "scrcpy-adb-connect").start();
        });
    }

    private void loadServerAsset() {
        AssetManager assetManager = getAssets();
        try (InputStream input = assetManager.open("scrcpy-server.jar");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            fileBase64 = Base64.encode(output.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            Log.e("Asset Manager", "Unable to load scrcpy server " + ScrcpySessionOptions.SERVER_VERSION, e);
            Toast.makeText(this, "The bundled scrcpy server is missing", Toast.LENGTH_LONG).show();
        }
    }

    private void waitForConnection() {
        new Thread(() -> {
            int attempts = 150;
            while (attempts-- > 0 && !scrcpy.check_socket_connection()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            runOnUiThread(() -> {
                if (scrcpy != null && scrcpy.check_socket_connection()) {
                    int[] resolution = scrcpy.get_remote_device_resolution();
                    remote_device_width = resolution[0];
                    remote_device_height = resolution[1];
                    first_time = false;
                    set_display_nd_touch();
                } else {
                    if (serviceBound) {
                        scrcpy.StopService();
                        unbindService(serviceConnection);
                        serviceBound = false;
                    }
                    first_time = true;
                    scrcpy_main();
                    Toast.makeText(context, "Connection timed out. Check ADB wireless debugging and the IP address.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "scrcpy-wait-for-stream").start();
    }

    private void showDisplayWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            startActivity(intent);
            Toast.makeText(this, "Allow display over other apps, then tap Start again", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, FloatService.class);
        intent.putExtra("ip", serverAdr);
        intent.putExtra("w", screenWidth);
        intent.putExtra("h", screenHeight);
        sessionOptions.putInto(intent);
        startService(intent);
        finish();
    }

    public void get_saved_preferences() {
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

        editTextServerHost.setText(getPreferences().getString("Server Address", ""));
        darkModeSwitch.setChecked(getPreferences().getBoolean(PREFERENCE_DARK_MODE, false));
        floatingWindowSwitch.setChecked(getPreferences().getBoolean(PREFERENCE_FLOATING_WINDOW, false));
        noControlSwitch.setChecked(getPreferences().getBoolean("No Control", false));
        navSwitch.setChecked(getPreferences().getBoolean("Nav Switch", false));
        audioSwitch.setChecked(getPreferences().getBoolean("Audio", false));
        powerOnSwitch.setChecked(getPreferences().getBoolean("Power On", true));
        turnScreenOffSwitch.setChecked(getPreferences().getBoolean("Turn Screen Off", false));
        powerOffOnCloseSwitch.setChecked(getPreferences().getBoolean("Power Off On Close", false));
        stayAwakeSwitch.setChecked(getPreferences().getBoolean("Stay Awake", false));
        showTouchesSwitch.setChecked(getPreferences().getBoolean("Show Touches", false));

        setSpinner(R.array.options_resolution_values, R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION, 2);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE, 2);
        setSpinner(R.array.options_fps_values, R.id.spinner_video_fps, PREFERENCE_SPINNER_FPS, 1);

        navSwitch.setVisibility(noControlSwitch.isChecked() ? View.GONE : View.VISIBLE);
        powerOnSwitch.setEnabled(!noControlSwitch.isChecked());
        turnScreenOffSwitch.setEnabled(!noControlSwitch.isChecked());
        noControlSwitch.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean("No Control", checked).apply();
            navSwitch.setVisibility(checked ? View.GONE : View.VISIBLE);
            powerOnSwitch.setEnabled(!checked);
            turnScreenOffSwitch.setEnabled(!checked);
            if (checked) {
                turnScreenOffSwitch.setChecked(false);
            }
        });
        navSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean("Nav Switch", checked).apply());
        audioSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean("Audio", checked).apply());
        powerOnSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean("Power On", checked).apply());
        turnScreenOffSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean("Turn Screen Off", checked).apply());
        powerOffOnCloseSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean("Power Off On Close", checked).apply());
        stayAwakeSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean("Stay Awake", checked).apply());
        showTouchesSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean("Show Touches", checked).apply());
        floatingWindowSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences().edit().putBoolean(PREFERENCE_FLOATING_WINDOW, checked).apply());
        darkModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean(PREFERENCE_DARK_MODE, checked).apply();
            recreate();
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        if (linearLayout == null || surfaceView == null || remote_device_width <= 0 || remote_device_height <= 0) {
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        if (ViewConfiguration.get(context).hasPermanentMenuKey()) {
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
        } else {
            Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(metrics);
        }

        float thisDeviceHeight = linearLayout.getHeight();
        float thisDeviceWidth = linearLayout.getWidth();
        if (nav && !no_control) {
            if (landscape) {
                thisDeviceWidth -= 96;
            } else {
                thisDeviceHeight -= 96;
            }
        }
        float remoteAspectRatio = remote_device_height / remote_device_width;
        if (!landscape) {
            float localAspectRatio = thisDeviceHeight / thisDeviceWidth;
            if (remoteAspectRatio > localAspectRatio) {
                float wantedWidth = thisDeviceHeight / remoteAspectRatio;
                int padding = (int) (thisDeviceWidth - wantedWidth) / 2;
                linearLayout.setPadding(padding, 0, padding, 0);
            } else if (remoteAspectRatio < localAspectRatio) {
                linearLayout.setPadding(0, (int) ((localAspectRatio - remoteAspectRatio) * thisDeviceWidth), 0, 0);
            }
        } else {
            float localAspectRatio = thisDeviceWidth / thisDeviceHeight;
            if (remoteAspectRatio > localAspectRatio) {
                float wantedHeight = thisDeviceWidth / remoteAspectRatio;
                int padding = (int) (thisDeviceHeight - wantedHeight) / 2;
                linearLayout.setPadding(0, padding, 0, padding);
            } else if (remoteAspectRatio < localAspectRatio) {
                int padding = (int) (((localAspectRatio - remoteAspectRatio) * thisDeviceHeight) / 2);
                linearLayout.setPadding(padding, 0, padding, 0);
            }
        }

        if (!no_control && scrcpy != null) {
            surfaceView.setOnTouchListener((view, event) -> scrcpy.touchevent(event, surfaceView.getWidth(), surfaceView.getHeight()));
        } else {
            surfaceView.setOnTouchListener(null);
        }

        if (nav && !no_control) {
            Button backButton = findViewById(R.id.back_button);
            Button homeButton = findViewById(R.id.home_button);
            Button appSwitchButton = findViewById(R.id.appswitch_button);
            backButton.setOnClickListener(view -> scrcpy.sendKeyevent(4));
            homeButton.setOnClickListener(view -> scrcpy.sendKeyevent(3));
            appSwitchButton.setOnClickListener(view -> scrcpy.sendKeyevent(187));
        }
    }

    private void setSpinner(int textArrayResource, int spinnerId, String preferenceId, int defaultPosition) {
        Spinner spinner = findViewById(spinnerId);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, textArrayResource,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPreferences().edit().putInt(preferenceId, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                getPreferences().edit().putInt(preferenceId, defaultPosition).apply();
            }
        });
        spinner.setSelection(getPreferences().getInt(preferenceId, defaultPosition));
    }

    private void getAttributes() {
        EditText editTextServerHost = findViewById(R.id.editText_server_host);
        serverAdr = editTextServerHost.getText().toString().trim();
        getPreferences().edit().putString("Server Address", serverAdr).apply();

        Spinner resolutionSpinner = findViewById(R.id.spinner_video_resolution);
        Spinner bitrateSpinner = findViewById(R.id.spinner_video_bitrate);
        Spinner fpsSpinner = findViewById(R.id.spinner_video_fps);
        String resolution = getResources().getStringArray(R.array.options_resolution_values)
                [resolutionSpinner.getSelectedItemPosition()];
        String[] dimensions = resolution.split("x");
        int resolutionWidth = Integer.parseInt(dimensions[0]);
        int resolutionHeight = Integer.parseInt(dimensions[1]);
        screenWidth = Math.min(resolutionWidth, resolutionHeight);
        screenHeight = Math.max(resolutionWidth, resolutionHeight);
        videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)
                [bitrateSpinner.getSelectedItemPosition()];
        int maxFps = Integer.parseInt(getResources().getStringArray(R.array.options_fps_values)
                [fpsSpinner.getSelectedItemPosition()]);

        Switch noControlSwitch = findViewById(R.id.switch0);
        Switch navSwitch = findViewById(R.id.switch1);
        sessionOptions = new ScrcpySessionOptions();
        sessionOptions.noControl = noControlSwitch.isChecked();
        sessionOptions.navBar = navSwitch.isChecked() && !sessionOptions.noControl;
        sessionOptions.audioEnabled = ((Switch) findViewById(R.id.switch_audio)).isChecked();
        sessionOptions.powerOn = ((Switch) findViewById(R.id.switch_power_on)).isChecked();
        sessionOptions.turnScreenOffOnConnect = ((Switch) findViewById(R.id.switch_turn_screen_off)).isChecked()
                && !sessionOptions.noControl;
        sessionOptions.powerOffOnClose = ((Switch) findViewById(R.id.switch_power_off_on_close)).isChecked();
        sessionOptions.stayAwake = ((Switch) findViewById(R.id.switch_stay_awake)).isChecked();
        sessionOptions.showTouches = ((Switch) findViewById(R.id.switch_show_touches)).isChecked();
        sessionOptions.floatingWindow = ((Switch) findViewById(R.id.switch_floating_mode)).isChecked();
        sessionOptions.maxSize = Math.max(resolutionWidth, resolutionHeight);
        sessionOptions.videoBitRate = videoBitrate;
        sessionOptions.maxFps = maxFps;

        no_control = sessionOptions.noControl;
        nav = sessionOptions.navBar;
        activeSessionOptions = sessionOptions.copy();

        getPreferences().edit()
                .putBoolean("No Control", sessionOptions.noControl)
                .putBoolean("Nav Switch", sessionOptions.navBar)
                .putBoolean("Audio", sessionOptions.audioEnabled)
                .putBoolean("Power On", sessionOptions.powerOn)
                .putBoolean("Turn Screen Off", sessionOptions.turnScreenOffOnConnect)
                .putBoolean("Power Off On Close", sessionOptions.powerOffOnClose)
                .putBoolean("Stay Awake", sessionOptions.stayAwake)
                .putBoolean("Show Touches", sessionOptions.showTouches)
                .putBoolean(PREFERENCE_FLOATING_WINDOW, sessionOptions.floatingWindow)
                .apply();
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences(PREFERENCE_KEY, MODE_PRIVATE);
    }

    private void start_screen_copy_magic() {
        setContentView(R.layout.surface);
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surfaceView = findViewById(R.id.decoder_surface);
        surface = surfaceView.getHolder().getSurface();
        LinearLayout navBar = findViewById(R.id.nav_button_bar);
        navBar.setVisibility(nav && !no_control ? View.VISIBLE : View.GONE);
        linearLayout = findViewById(R.id.container1);
        start_Scrcpy_service();
    }

    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        if (activeSessionOptions != null) {
            activeSessionOptions.putInto(intent);
        }
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public void loadNewRotation() {
        if (scrcpy == null) {
            return;
        }
        if (first_time) {
            int[] resolution = scrcpy.get_remote_device_resolution();
            remote_device_width = resolution[0];
            remote_device_height = resolution[1];
            first_time = false;
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        result_of_Rotation = true;
        landscape = !landscape;
        swapDimensions();
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void swapDimensions() {
        int temp = screenHeight;
        screenHeight = screenWidth;
        screenWidth = temp;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (serviceBound && scrcpy != null) {
            scrcpy.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!first_time && !result_of_Rotation) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (serviceBound && scrcpy != null) {
                linearLayout = findViewById(R.id.container1);
                scrcpy.resume();
            }
        }
        result_of_Rotation = false;
    }

    @Override
    public void onBackPressed() {
        if (timestamp == 0) {
            timestamp = SystemClock.uptimeMillis();
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show();
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1_000) {
                timestamp = 0;
                if (serviceBound && scrcpy != null) {
                    scrcpy.StopService();
                    unbindService(serviceConnection);
                    serviceBound = false;
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            } else {
                timestamp = now;
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY && serviceBound && scrcpy != null && !no_control) {
            scrcpy.setRemoteDisplayPower(sensorEvent.values[0] != 0);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No sensor calibration is needed for the power toggle.
    }

    private String wifiIpAddress() {
        try {
            InetAddress ipv6 = null;
            InetAddress loopback = null;
            for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements();) {
                NetworkInterface networkInterface = interfaces.nextElement();
                for (Enumeration<InetAddress> addresses = networkInterface.getInetAddresses(); addresses.hasMoreElements();) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress()) {
                        loopback = address;
                    } else if (address instanceof Inet4Address) {
                        return address.getHostAddress();
                    } else if (address instanceof Inet6Address) {
                        ipv6 = address;
                    }
                }
            }
            if (ipv6 != null) {
                return ipv6.getHostAddress();
            }
            return loopback == null ? null : loopback.getHostAddress();
        } catch (SocketException e) {
            Log.w("Network", "Could not determine the local IP address", e);
            return null;
        }
    }
}
