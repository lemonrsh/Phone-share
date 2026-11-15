package com.lemonrsh.phoneshare;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds scrcpy sessions where another phone is mirroring <em>this</em> phone. The scrcpy
 * server started by the other phone's app listens on a loopback port (SessionBeacon in the
 * server module) and reports who started it; this class asks it, and can ask it to stop.
 * Works without root, as long as the other phone also runs this app (r11 or newer).
 */
final class IncomingSessions {

    static final int FIRST_PORT = 27200;
    static final int PORT_COUNT = 5;
    private static final int CONNECT_TIMEOUT_MS = 250;
    private static final int READ_TIMEOUT_MS = 1500;
    private static final long POLL_INTERVAL_MS = 2500;

    static final class Session {
        final int port;
        final String clientIp;
        final String clientName;
        final long startMillis;

        Session(int port, String clientIp, String clientName, long startMillis) {
            this.port = port;
            this.clientIp = clientIp;
            this.clientName = clientName;
            this.startMillis = startMillis;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Session)) {
                return false;
            }
            Session session = (Session) other;
            return port == session.port && startMillis == session.startMillis
                    && clientIp.equals(session.clientIp) && clientName.equals(session.clientName);
        }

        @Override
        public int hashCode() {
            return port * 31 + (int) startMillis;
        }
    }

    interface Listener {
        void onIncomingSessions(List<Session> sessions);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "incoming-sessions");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean running;
    private List<Session> last = Collections.emptyList();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            executor.execute(() -> {
                final List<Session> sessions = scan();
                handler.post(() -> deliver(sessions));
            });
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    IncomingSessions(Listener listener) {
        this.listener = listener;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        last = null; // always deliver the first result
        handler.post(poll);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    /** Asks the session to end, then refreshes the list. */
    void disconnect(final Session session, final Runnable onDone) {
        executor.execute(() -> {
            boolean ok = request(session.port, "DISCONNECT") != null;
            DiagnosticLog.i("incoming", "Disconnect " + session.clientIp + " on port " + session.port
                    + (ok ? ": ok" : ": no answer"));
            final List<Session> sessions = scan();
            handler.post(() -> {
                deliver(sessions);
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    private void deliver(List<Session> sessions) {
        if (!running && last != null) {
            return;
        }
        if (sessions.equals(last)) {
            return;
        }
        last = sessions;
        listener.onIncomingSessions(sessions);
    }

    /** Blocking: every session running right now. Not on the main thread. */
    static List<Session> scanNow() {
        return scan();
    }

    /** Blocking: asks one session to end. Not on the main thread. */
    static boolean disconnectNow(Session session) {
        return request(session.port, "DISCONNECT") != null;
    }

    private static List<Session> scan() {
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < PORT_COUNT; ++i) {
            int port = FIRST_PORT + i;
            Session session = parse(port, request(port, "STATUS"));
            if (session != null) {
                sessions.add(session);
            }
        }
        return sessions;
    }

    static Session parse(int port, String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 4 || !"SCRCPY".equals(parts[0])) {
            return null;
        }
        long start;
        try {
            start = Long.parseLong(parts[3].trim());
        } catch (NumberFormatException e) {
            start = 0;
        }
        return new Session(port, parts[1].trim(), parts[2].trim(), start);
    }

    private static String request(int port, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
