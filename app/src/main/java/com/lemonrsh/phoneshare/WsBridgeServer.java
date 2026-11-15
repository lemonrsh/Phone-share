package com.lemonrsh.phoneshare;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs on the shared phone. cloudflared forwards the public trycloudflare.com address to this
 * loopback HTTP server; each WebSocket request {@code GET /<target>?pin=<PIN>} is checked
 * against the PIN and bridged to a local TCP port ("adb" = 5555, "scrcpy" = 7007).
 */
final class WsBridgeServer {
    static final String ERROR_HEADER = "X-Scrcpy-Error";
    private static final int TARGET_CONNECT_TIMEOUT_MS = 3000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;

    private final ServerSocket serverSocket;
    private final byte[] pin;
    private final Map<String, Integer> targets;
    private volatile boolean running = true;

    WsBridgeServer(String pin, Map<String, Integer> targets) throws IOException {
        this.pin = pin.getBytes(StandardCharsets.UTF_8);
        this.targets = Collections.unmodifiableMap(new HashMap<>(targets));
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32);
    }

    int getPort() {
        return serverSocket.getLocalPort();
    }

    void start() {
        Thread thread = new Thread(this::acceptLoop, "share-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    DiagnosticLog.e("share", "Bridge stopped accepting", e);
                }
                return;
            }
            Thread thread = new Thread(() -> handle(socket), "share-bridge-conn");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void handle(Socket socket) {
        Socket tcp = null;
        boolean bridged = false;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            WsFrames.Head head = WsFrames.readHead(in);
            String[] parts = head.firstLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                respond(out, 405, "Method Not Allowed", null);
                return;
            }
            String path = parts[1];
            String query = "";
            int question = path.indexOf('?');
            if (question >= 0) {
                query = path.substring(question + 1);
                path = path.substring(0, question);
            }
            String target = path.startsWith("/") ? path.substring(1) : path;
            boolean upgrade = "websocket".equalsIgnoreCase(head.header("Upgrade"));
            String key = head.header("Sec-WebSocket-Key");
            if (!upgrade || key.isEmpty()) {
                if (target.isEmpty() || "connect".equals(target)) {
                    // The share link opened in a browser: a page that opens Phone Share.
                    respondPage(out, ShareLink.landingPage());
                } else if ("favicon.ico".equals(target)) {
                    respond(out, 404, "Not Found", "No icon");
                } else {
                    respond(out, 200, "OK", null); // health check
                }
                return;
            }
            if (!pinMatches(queryParam(query, "pin"))) {
                DiagnosticLog.i("share", "Rejected a connection with a wrong PIN");
                respond(out, 403, "Forbidden", "Wrong PIN");
                return;
            }
            Integer port = targets.get(target);
            if (port == null) {
                respond(out, 404, "Not Found", "Unknown target " + target);
                return;
            }
            tcp = new Socket();
            try {
                tcp.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), TARGET_CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                respond(out, 502, "Bad Gateway", target + " is not listening on port " + port + " of the shared phone");
                return;
            }
            tcp.setTcpNoDelay(true);
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + WsFrames.acceptKey(key) + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            socket.setSoTimeout(0);
            DiagnosticLog.i("share", "Tunnel connection opened to " + target + " (port " + port + ")");
            new WsPipe(socket, in, out, false, tcp, "share-" + target).start();
            bridged = true;
        } catch (IOException | RuntimeException e) {
            // A broken handshake: nothing to report to the other side.
        } finally {
            if (!bridged) {
                closeQuietly(tcp);
                closeQuietly(socket);
            }
        }
    }

    private boolean pinMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(pin, candidate.getBytes(StandardCharsets.UTF_8));
    }

    static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            if (key.equals(name)) {
                String value = equals >= 0 ? pair.substring(equals + 1) : "";
                try {
                    return URLDecoder.decode(value, "UTF-8");
                } catch (IOException | IllegalArgumentException e) {
                    return value;
                }
            }
        }
        return null;
    }

    private static void respond(OutputStream out, int code, String reason, String error) throws IOException {
        String body = error == null ? "scrcpy-android share tunnel\n" : error + "\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (error != null) {
            head.append(ERROR_HEADER).append(": ").append(error.replace('\r', ' ').replace('\n', ' ')).append("\r\n");
        }
        head.append("Content-Type: text/plain; charset=utf-8\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static void respondPage(OutputStream out, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "X-Robots-Tag: noindex\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException | RuntimeException ignored) {
            // ignore
        }
    }
}
