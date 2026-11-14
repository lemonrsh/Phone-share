package org.las2mile.scrcpy;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide connection state shared by the full-screen viewer and the
 * floating window, so that the main screen can always say "Connected" or
 * "Disconnected". Updates may come from any thread; listeners are always
 * called on the main thread.
 */
public final class SessionState {
    public enum Status { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    public interface Listener {
        void onSessionStateChanged(Status status, String host, String deviceName, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Status status = Status.IDLE;
    private static volatile String host;
    private static volatile String deviceName;
    private static volatile String message;

    private SessionState() {
    }

    public static Status getStatus() {
        return status;
    }

    public static String getHost() {
        return host;
    }

    public static String getDeviceName() {
        return deviceName;
    }

    public static String getMessage() {
        return message;
    }

    public static void connecting(String newHost) {
        set(Status.CONNECTING, newHost, null, null);
    }

    public static void connected(String newHost, String newDeviceName) {
        set(Status.CONNECTED, newHost, newDeviceName, null);
        DiagnosticLog.i("app", "Connected to " + describe(newHost, newDeviceName));
    }

    public static void disconnected(String reason) {
        Status previous = status;
        if (previous == Status.IDLE) {
            return;
        }
        set(Status.DISCONNECTED, host, deviceName, reason);
        DiagnosticLog.i("app", "Disconnected" + (reason == null ? "" : ": " + reason));
    }

    /** "Pixel 8 (192.168.1.5:5555)", or just the host when the name is unknown. */
    public static String describe(String host, String deviceName) {
        String name = deviceName == null ? "" : deviceName.trim();
        if (name.isEmpty()) {
            return host == null ? "the device" : host;
        }
        return host == null ? name : name + " (" + host + ")";
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
        final Status currentStatus = status;
        final String currentHost = host;
        final String currentName = deviceName;
        final String currentMessage = message;
        MAIN.post(() -> {
            if (LISTENERS.contains(listener)) {
                listener.onSessionStateChanged(currentStatus, currentHost, currentName, currentMessage);
            }
        });
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static synchronized void set(Status newStatus, String newHost, String newDeviceName, String newMessage) {
        status = newStatus;
        host = newHost;
        deviceName = newDeviceName;
        message = newMessage;
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onSessionStateChanged(newStatus, newHost, newDeviceName, newMessage);
            }
        });
    }
}
