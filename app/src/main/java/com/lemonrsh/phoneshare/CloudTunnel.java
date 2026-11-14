package com.lemonrsh.phoneshare;

import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Controller side of the Cloudflare share tunnel. A share code looks like
 * {@code blue-river-cat.trycloudflare.com#123456}. Each connection the app would normally open
 * to the target (ADB on 5555, scrcpy on 7007) is instead opened as a WebSocket to
 * {@code wss://<address>/<adb|scrcpy>?pin=<PIN>} and handed to the caller as an ordinary
 * loopback {@link Socket}, so AdbLauncher and ScrcpyClient need no other changes.
 */
final class CloudTunnel {
    static final String SUFFIX = ".trycloudflare.com";
    static final String TARGET_ADB = "adb";
    static final String TARGET_SCRCPY = "scrcpy";
    private static final int MIN_TIMEOUT_MS = 15_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> PINS = new ConcurrentHashMap<>();

    /** Thrown when the tunnel answered but refused the connection. */
    static final class TunnelException extends IOException {
        final int status;

        TunnelException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private CloudTunnel() {
    }

    static boolean isTunnelHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(SUFFIX);
    }

    static void rememberPin(String host, String pin) {
        if (host != null && pin != null) {
            PINS.put(host.toLowerCase(Locale.ROOT), pin);
        }
    }

    static String pinFor(String host) {
        return host == null ? null : PINS.get(host.toLowerCase(Locale.ROOT));
    }

    /** Connects the (unconnected) socket through the tunnel to the given target. */
    static void connect(Socket socket, String host, String target, int timeoutMs) throws IOException {
        String pin = pinFor(host);
        if (pin == null) {
            throw new TunnelException(0, "The PIN is missing. Paste the whole share code, for example " + host + "#123456.");
        }
        connectVia(socket, host, 443, true, "/" + target + "?pin=" + pin, Math.max(timeoutMs, MIN_TIMEOUT_MS), target);
    }

    /** Also used by the unit tests with a plain (non-TLS) bridge on the loopback interface. */
    static void connectVia(Socket socket, String host, int port, boolean tls, String path, int timeoutMs, String name)
            throws IOException {
        Socket ws = openWebSocket(host, port, tls, path, timeoutMs);
        boolean ok = false;
        ServerSocket local = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        try {
            socket.connect(local.getLocalSocketAddress(), 3000);
            local.setSoTimeout(3000);
            Socket peer = local.accept();
            peer.setTcpNoDelay(true);
            new WsPipe(ws, new BufferedInputStream(ws.getInputStream(), 64 * 1024), ws.getOutputStream(), true, peer,
                    "tunnel-" + name).start();
            ok = true;
        } finally {
            local.close();
            if (!ok) {
                try {
                    ws.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static Socket openWebSocket(String host, int port, boolean tls, String path, int timeoutMs) throws IOException {
        Socket raw = new Socket();
        Socket socket = raw;
        boolean ok = false;
        try {
            try {
                raw.connect(new InetSocketAddress(host, port), timeoutMs);
            } catch (UnknownHostException e) {
                throw new TunnelException(0, "The share address " + host + " was not found. A new address can take "
                        + "up to a minute to start working; otherwise check the code or ask for a new one.");
            }
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);
            raw.setSoTimeout(timeoutMs);
            if (tls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
                enableSni(ssl, host);
                ssl.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                    throw new SSLPeerUnverifiedException("The certificate does not match " + host);
                }
                socket = ssl;
            }

            byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            String key = WsFrames.base64(keyBytes);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + (port == 443 || port == 80 ? "" : ":" + port) + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "User-Agent: scrcpy-android\r\n\r\n";
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = socket.getInputStream();
            WsFrames.Head head = WsFrames.readHead(in);
            int status = statusCode(head.firstLine);
            if (status != 101) {
                throw new TunnelException(status, describeStatus(status, head.header(WsBridgeServer.ERROR_HEADER)));
            }
            if (!WsFrames.acceptKey(key).equals(head.header("Sec-WebSocket-Accept"))) {
                throw new TunnelException(status, "The share address answered with an invalid WebSocket handshake.");
            }
            socket.setSoTimeout(0);
            ok = true;
            return socket;
        } finally {
            if (!ok) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // ignore
                }
                if (socket != raw) {
                    try {
                        raw.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    static int statusCode(String statusLine) {
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }

    static String describeStatus(int status, String bridgeError) {
        switch (status) {
            case 403:
                return "Wrong PIN. Check the share code shown on the shared phone.";
            case 502:
                if (bridgeError.startsWith(TARGET_ADB)) {
                    return "ADB over Wi-Fi (port 5555) is off on the shared phone. Turn it on there "
                            + "(adb tcpip 5555 from a computer) and share again.";
                }
                if (!bridgeError.isEmpty()) {
                    return "The shared phone could not open the connection: " + bridgeError + ".";
                }
                return "The shared phone is not answering (HTTP 502). Is sharing still on?";
            case 404:
            case 530:
                return "This share code is no longer active. Ask for a new code.";
            default:
                return "The share address answered HTTP " + status
                        + (bridgeError.isEmpty() ? "" : ": " + bridgeError) + ".";
        }
    }

    @SuppressWarnings("NewApi")
    private static void enableSni(SSLSocket socket, String host) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
                socket.setSSLParameters(parameters);
                return;
            }
        } catch (RuntimeException ignored) {
            // fall back to the Conscrypt method below
        }
        try {
            Method setHostname = socket.getClass().getMethod("setHostname", String.class);
            setHostname.invoke(socket, host);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Conscrypt usually enables SNI on its own for layered sockets.
        }
    }
}
