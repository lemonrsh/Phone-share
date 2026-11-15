package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.util.Ln;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lets the scrcpy-android app installed on the mirrored phone see who is connected and end
 * the session, without root. The server already runs as the shell user, which may listen on
 * a loopback TCP port; any app on the same phone may connect to it, nothing else can.
 *
 * <p>Protocol (one text line each way):
 * <ul>
 *     <li>{@code STATUS} answers {@code SCRCPY<TAB>ip<TAB>name<TAB>startMillis}</li>
 *     <li>{@code DISCONNECT} answers {@code OK} and ends the session</li>
 * </ul>
 * Several sessions can run at once, so the first free port of a small range is used.
 *
 * <p>It also tells the Phone Share app on this phone (if installed) when the session starts and
 * ends, with an explicit broadcast sent through {@code am} as the shell user, so that the app
 * shows a "connected" notification with a Disconnect button even when it is not open. The
 * app's receiver requires the DUMP permission, which the shell has and normal apps cannot get.
 */
public final class SessionBeacon {

    public static final int FIRST_PORT = 27200;
    public static final int PORT_COUNT = 5;

    private static final String APP_PACKAGE = "com.lemonrsh.phoneshare";
    private static final String APP_RECEIVER = APP_PACKAGE + "/.IncomingSessionReceiver";
    private static final String ACTION_PREFIX = APP_PACKAGE + ".action.";
    // Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND
    private static final String BROADCAST_FLAGS = String.valueOf(0x00000020 | 0x10000000);
    private static final long NOTIFY_END_WAIT_MS = 3000;

    private final ServerSocket serverSocket;
    private final int port;
    private final String clientIp;
    private final String clientName;
    private final long startMillis = System.currentTimeMillis();
    private final Runnable onDisconnect;
    private final Thread thread;

    private SessionBeacon(ServerSocket serverSocket, int port, String clientIp, String clientName, Runnable onDisconnect) {
        this.serverSocket = serverSocket;
        this.port = port;
        this.clientIp = clientIp == null ? "" : clientIp;
        this.clientName = clientName == null ? "" : clientName;
        this.onDisconnect = onDisconnect;
        this.thread = new Thread(this::loop, "session-beacon");
        this.thread.setDaemon(true);
    }

    /** Starts the beacon, or returns null when no port is free (the session still works). */
    public static SessionBeacon start(String clientIp, String clientName, Runnable onDisconnect) {
        for (int i = 0; i < PORT_COUNT; ++i) {
            int port = FIRST_PORT + i;
            ServerSocket socket = null;
            try {
                socket = new ServerSocket();
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
                SessionBeacon beacon = new SessionBeacon(socket, port, clientIp, clientName, onDisconnect);
                beacon.thread.start();
                Ln.i("Session beacon listening on 127.0.0.1:" + port);
                beacon.notifyApp("SESSION_STARTED");
                return beacon;
            } catch (IOException | RuntimeException e) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
        }
        Ln.w("Session beacon: no free port");
        return null;
    }

    private void loop() {
        while (!serverSocket.isClosed()) {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(2000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String request = reader.readLine();
                OutputStream out = socket.getOutputStream();
                if ("DISCONNECT".equals(request)) {
                    out.write("OK\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Ln.i("Session ended from the mirrored phone");
                    onDisconnect.run();
                } else {
                    String line = "SCRCPY\t" + clean(clientIp) + "\t" + clean(clientName) + "\t" + startMillis + "\n";
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | RuntimeException e) {
                if (serverSocket.isClosed()) {
                    return;
                }
            }
        }
    }

    private static String clean(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public void stop() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // ignore
        }
        // Wait a little so that the app removes its notification before this process exits.
        Thread thread = notifyApp("SESSION_ENDED");
        try {
            thread.join(NOTIFY_END_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends the broadcast in the background; a phone without the app just ignores it. */
    private Thread notifyApp(String action) {
        final String[] command = {
                "/system/bin/am", "broadcast",
                "-n", APP_RECEIVER,
                "-a", ACTION_PREFIX + action,
                "-f", BROADCAST_FLAGS,
                "--ei", "port", String.valueOf(port),
                "--el", "start", String.valueOf(startMillis),
                "--es", "ip", clientIp.isEmpty() ? "-" : clean(clientIp),
                "--es", "name", clientName.isEmpty() ? "-" : clean(clientName),
        };
        Thread thread = new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec(command);
                process.getOutputStream().close();
                drain(process);
                int code = process.waitFor();
                Ln.d("Told the app on this phone: " + action + " (am exit " + code + ")");
            } catch (IOException | RuntimeException e) {
                Ln.w("Could not tell the app on this phone: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "session-notify");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void drain(Process process) throws IOException {
        byte[] buffer = new byte[1024];
        java.io.InputStream out = process.getInputStream();
        while (out.read(buffer) != -1) {
            // discard
        }
        java.io.InputStream err = process.getErrorStream();
        while (err.read(buffer) != -1) {
            // discard
        }
    }
}
