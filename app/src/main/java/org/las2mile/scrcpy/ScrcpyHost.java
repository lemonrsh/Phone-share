package org.las2mile.scrcpy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
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

/** Connects an overlay window to the scrcpy service. */
public class ScrcpyHost implements Scrcpy.ServiceCallbacks {
    private static final String TAG = "ScrcpyHost";

    private Context context;
    private Scrcpy scrcpy;
    private boolean serviceBound;
    private int screenWidth;
    private int screenHeight;
    private String serverAddress;
    private Surface surface;
    private byte[] serverBase64;
    private ScrcpySessionOptions sessionOptions;
    private SendCommands sendCommands;
    private ConnectCallBack connectCallBack;

    public void setConnectCallBack(ConnectCallBack connectCallBack) {
        this.connectCallBack = connectCallBack;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(ScrcpyHost.this);
            serviceBound = true;
            scrcpy.start(surface, serverAddress, screenHeight, screenWidth);

            new Thread(() -> {
                int attempts = 100;
                while (attempts-- > 0 && !scrcpy.check_socket_connection()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (scrcpy.check_socket_connection()) {
                    int[] dimensions = scrcpy.get_remote_device_resolution();
                    postToMain(() -> {
                        if (connectCallBack != null) {
                            connectCallBack.onConnect(dimensions[0], dimensions[1]);
                        }
                    });
                } else {
                    postToMain(() -> Toast.makeText(context, "Connection timed out", Toast.LENGTH_SHORT).show());
                }
            }, "scrcpy-overlay-connect").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    public void connect(Context context, String clientIp, int width, int height, int bitrate,
                        ScrcpySessionOptions options, Surface display) {
        this.context = context.getApplicationContext();
        this.screenWidth = width;
        this.screenHeight = height;
        this.surface = display;
        this.serverAddress = clientIp;
        this.sessionOptions = options.copy();
        this.sessionOptions.videoBitRate = bitrate;

        try {
            loadServerAsset();
        } catch (IOException e) {
            Log.e(TAG, "Could not read the scrcpy server asset", e);
            Toast.makeText(context, "scrcpy server asset is missing", Toast.LENGTH_SHORT).show();
            return;
        }

        sendCommands = new SendCommands();
        String localIp = wifiIpAddress();
        if (serverAddress == null || serverAddress.trim().isEmpty()) {
            Toast.makeText(context, "Server address is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        if (localIp == null || localIp.trim().isEmpty()) {
            Toast.makeText(context, "Could not determine this device's Wi-Fi address", Toast.LENGTH_SHORT).show();
            return;
        }

        int result = sendCommands.SendAdbCommands(this.context, serverBase64, serverAddress, localIp, sessionOptions);
        if (result == 0) {
            startScrcpyService();
        } else {
            Toast.makeText(context, "Network or ADB connection failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadServerAsset() throws IOException {
        AssetManager assetManager = context.getAssets();
        try (InputStream input = assetManager.open("scrcpy-server.jar");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            serverBase64 = Base64.encode(output.toByteArray(), Base64.NO_WRAP);
        }
    }

    private void startScrcpyService() {
        Intent intent = new Intent(context, Scrcpy.class);
        sessionOptions.putInto(intent);
        context.startService(intent);
        serviceBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void postToMain(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
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
            Log.w(TAG, "Could not find a network address", e);
            return null;
        }
    }

    public boolean touch(MotionEvent motionEvent, int surfaceWidth, int surfaceHeight) {
        return scrcpy != null && scrcpy.touchevent(motionEvent, surfaceWidth, surfaceHeight);
    }

    public void keyEvent(int keyCode) {
        if (scrcpy != null) {
            scrcpy.sendKeyevent(keyCode);
        }
    }

    public void destroy() {
        if (scrcpy != null) {
            scrcpy.StopService();
        }
        if (serviceBound && context != null) {
            try {
                context.unbindService(serviceConnection);
            } catch (IllegalArgumentException ignored) {
                // The system already disconnected the service.
            }
            serviceBound = false;
        }
        if (context != null) {
            context.stopService(new Intent(context, Scrcpy.class));
        }
    }

    @Override
    public void loadNewRotation() {
        if (scrcpy == null) {
            return;
        }
        int[] dimensions = scrcpy.get_remote_device_resolution();
        postToMain(() -> {
            if (connectCallBack != null) {
                connectCallBack.onConnect(dimensions[0], dimensions[1]);
            }
        });
        scrcpy.setParms(surface, screenWidth, screenHeight);
    }

    public interface ConnectCallBack {
        void onConnect(float width, float height);
    }
}
