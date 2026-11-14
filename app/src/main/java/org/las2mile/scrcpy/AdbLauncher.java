package org.las2mile.scrcpy;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Base64;
import android.util.Log;

import com.tananaev.adblib.AdbAuthenticationFailedException;
import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Connects to the target's ADB daemon over TCP, installs the bundled scrcpy
 * server (only when the copy on the device differs) and starts it.
 *
 * <p>All methods block and must run on a background thread. A launch can be
 * aborted from another thread with {@link #cancel()}.
 */
public final class AdbLauncher {
    private static final String TAG = "AdbLauncher";

    private static final String SERVER_PATH = "/data/local/tmp/scrcpy-server.jar";
    private static final String UPLOAD_PATH = "/data/local/tmp/scrcpy-server.b64";
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 5_000;
    private static final long AUTH_TIMEOUT_SECONDS = 60;
    private static final long SHELL_TIMEOUT_MS = 15_000;
    private static final long LAUNCH_WATCHDOG_MS = (AUTH_TIMEOUT_SECONDS + 120) * 1000;
    /** Base64 characters per shell line; stays well below the 4 KiB tty line limit. */
    private static final int UPLOAD_CHUNK_CHARS = 3_000;

    private static final Pattern READY = Pattern.compile("SCRCPY_READY");
    private static final Pattern HASH = Pattern.compile("SCRCPY_HASH_([0-9a-fA-F]*)_END");

    private static final Object CACHE_LOCK = new Object();
    private static AdbCrypto cachedCrypto;
    private static byte[] cachedServer;
    private static String cachedServerMd5;

    private final Object stateLock = new Object();
    private Socket socket;
    private Session session;
    private boolean cancelled;

    /** User-presentable launch failure. */
    public static final class LaunchException extends Exception {
        LaunchException(String message) {
            super(message);
        }

        LaunchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Aborts a running {@link #launch} call; safe from any thread. */
    public void cancel() {
        Socket currentSocket;
        Session currentSession;
        synchronized (stateLock) {
            cancelled = true;
            currentSocket = socket;
            currentSession = session;
        }
        if (currentSession != null) {
            currentSession.closeAsync();
        }
        closeQuietly(currentSocket);
    }

    public Session launch(Context context, HostAddress address, ScrcpySessionOptions options) throws LaunchException {
        Context appContext = context.getApplicationContext();
        byte[] server = loadServer(appContext);
        String serverMd5;
        synchronized (CACHE_LOCK) {
            serverMd5 = cachedServerMd5;
        }
        AdbCrypto crypto = loadCrypto(appContext);

        Socket newSocket = new Socket();
        synchronized (stateLock) {
            if (cancelled) {
                throw new LaunchException("Connection cancelled");
            }
            socket = newSocket;
        }

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(LAUNCH_WATCHDOG_MS);
                Log.w(TAG, "Launch watchdog fired");
                closeQuietly(newSocket);
            } catch (InterruptedException ignored) {
                // Launch finished in time.
            }
        }, "scrcpy-adb-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        boolean success = false;
        Session newSession = null;
        try {
            connectSocket(newSocket, address);
            String localAddress = hostAddress(newSocket.getLocalAddress());

            AdbConnection connection = AdbConnection.create(newSocket, crypto);
            boolean connected;
            try {
                connected = connection.connect(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS, false);
            } catch (AdbAuthenticationFailedException e) {
                throw new LaunchException("The target rejected this device's ADB key.", e);
            } catch (IOException e) {
                throw new LaunchException(isCancelled() ? "Connection cancelled"
                        : "The target closed the ADB connection. Make sure ADB debugging is enabled and "
                        + "accept the \"Allow debugging\" prompt on the target.", e);
            }
            if (!connected) {
                throw new LaunchException("Timed out waiting for ADB authorization. Accept the "
                        + "\"Allow debugging\" prompt on the target device and try again.");
            }

            AdbStream stream = connection.open("shell:");
            newSession = new Session(connection, stream, localAddress);
            synchronized (stateLock) {
                if (cancelled) {
                    throw new LaunchException("Connection cancelled");
                }
                session = newSession;
            }

            newSession.prepareShell();
            newSession.installServer(server, serverMd5);
            newSession.startServer(buildServerCommand(options, localAddress));
            success = true;
            return newSession;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchException("Connection cancelled", e);
        } catch (IOException e) {
            if (isCancelled()) {
                throw new LaunchException("Connection cancelled", e);
            }
            throw new LaunchException("ADB connection failed: " + describe(e), e);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unexpected ADB failure", e);
            throw new LaunchException("ADB connection failed: " + describe(e), e);
        } finally {
            watchdog.interrupt();
            if (!success) {
                if (newSession != null) {
                    newSession.closeAsync();
                }
                closeQuietly(newSocket);
            }
            synchronized (stateLock) {
                socket = null;
                session = null;
            }
        }
    }

    private boolean isCancelled() {
        synchronized (stateLock) {
            return cancelled;
        }
    }

    private static void connectSocket(Socket socket, HostAddress address) throws LaunchException {
        try {
            socket.connect(new InetSocketAddress(address.host, address.port), SOCKET_CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
        } catch (UnknownHostException e) {
            throw new LaunchException("Unknown host: " + address.host, e);
        } catch (SocketTimeoutException e) {
            throw new LaunchException("Timed out reaching " + address + ". Check the IP address and that both "
                    + "devices are on the same Wi-Fi network.", e);
        } catch (ConnectException e) {
            throw new LaunchException("ADB is not listening on " + address + ". Enable ADB over TCP/IP on the "
                    + "target (for example \"adb tcpip " + address.port + "\").", e);
        } catch (NoRouteToHostException e) {
            throw new LaunchException("No route to " + address + ". Check the Wi-Fi connection.", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new LaunchException("Could not connect to " + address + ": " + describe(e), e);
        }
    }

    static String buildServerCommand(ScrcpySessionOptions options, String localAddress) {
        StringBuilder command = new StringBuilder();
        command.append("CLASSPATH=").append(SERVER_PATH);
        command.append(" app_process / com.genymobile.scrcpy.Server ").append(ScrcpySessionOptions.SERVER_VERSION);
        command.append(" log_level=info");
        command.append(" tcp_port=").append(ScrcpySessionOptions.SERVER_PORT);
        command.append(" tcp_client=").append(localAddress);
        command.append(" tunnel_forward=false");
        command.append(" video=true audio=").append(options.audioEnabled);
        command.append(" control=").append(!options.noControl);
        command.append(" video_codec=h264 audio_codec=aac");
        command.append(" max_size=").append(options.maxSize);
        command.append(" video_bit_rate=").append(options.videoBitRate);
        command.append(" max_fps=").append(options.maxFps);
        command.append(" power_on=").append(options.powerOn && !options.noControl);
        command.append(" power_off_on_close=").append(options.powerOffOnClose);
        command.append(" stay_awake=").append(options.stayAwake);
        command.append(" show_touches=").append(options.showTouches);
        // The viewer never reads device messages, so do not make the server send clipboard updates.
        command.append(" clipboard_autosync=false");
        command.append(" cleanup=true send_dummy_byte=true send_device_meta=true");
        command.append(" send_frame_meta=true send_stream_meta=true");
        return command.toString();
    }

    private static byte[] loadServer(Context context) throws LaunchException {
        synchronized (CACHE_LOCK) {
            if (cachedServer != null) {
                return cachedServer;
            }
            AssetManager assets = context.getAssets();
            try (InputStream input = assets.open("scrcpy-server.jar");
                 ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024)) {
                byte[] buffer = new byte[16 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
                byte[] bytes = output.toByteArray();
                if (bytes.length == 0) {
                    throw new LaunchException("The bundled scrcpy server is empty.");
                }
                cachedServerMd5 = md5Hex(bytes);
                cachedServer = bytes;
                return bytes;
            } catch (IOException e) {
                throw new LaunchException("The bundled scrcpy server is missing from this APK.", e);
            }
        }
    }

    private static AdbCrypto loadCrypto(Context context) throws LaunchException {
        synchronized (CACHE_LOCK) {
            if (cachedCrypto != null) {
                return cachedCrypto;
            }
            AdbBase64 base64 = data -> Base64.encodeToString(data, Base64.NO_WRAP);
            File privateKey = new File(context.getFilesDir(), "priv.key");
            File publicKey = new File(context.getFilesDir(), "pub.key");
            AdbCrypto crypto = null;
            if (privateKey.isFile() && publicKey.isFile()) {
                try {
                    crypto = AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey);
                } catch (Exception e) {
                    Log.w(TAG, "Stored ADB key is unreadable; generating a new one", e);
                }
            }
            if (crypto == null) {
                try {
                    crypto = AdbCrypto.generateAdbKeyPair(base64);
                } catch (NoSuchAlgorithmException e) {
                    throw new LaunchException("This device cannot generate an RSA key for ADB.", e);
                }
                try {
                    crypto.saveAdbKeyPair(privateKey, publicKey);
                } catch (IOException e) {
                    // The key still works for this session; the target will ask again next time.
                    Log.w(TAG, "Could not save the ADB key", e);
                }
            }
            cachedCrypto = crypto;
            return crypto;
        }
    }

    static String md5Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String hostAddress(InetAddress address) {
        String text = address.getHostAddress();
        if (address instanceof Inet6Address) {
            int scope = text.indexOf('%');
            if (scope >= 0) {
                text = text.substring(0, scope);
            }
        }
        return text;
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    /**
     * A running server. The interactive shell stays open while mirroring: the
     * server's log is drained from it, and closing the session hangs up the
     * shell so that the server stops.
     */
    public static final class Session {
        private final AdbConnection connection;
        private final AdbStream stream;
        private final String localAddress;
        private final ShellOutput output = new ShellOutput();
        private final Thread reader;
        private volatile boolean serverStarted;
        private volatile boolean closed;

        Session(AdbConnection connection, AdbStream stream, String localAddress) {
            this.connection = connection;
            this.stream = stream;
            this.localAddress = localAddress;
            reader = new Thread(this::readLoop, "scrcpy-adb-shell");
            reader.setDaemon(true);
            reader.start();
        }

        public String getLocalAddress() {
            return localAddress;
        }

        /** False once the shell (and therefore the server process) has exited. */
        public boolean isServerAlive() {
            return !closed && !output.isClosed();
        }

        /** A short description of why the server stopped, if it printed one. */
        public String describeServerFailure() {
            String error = output.lastErrorLine();
            if (error != null) {
                return error.length() > 300 ? error.substring(0, 300) + "…" : error;
            }
            return null;
        }

        public void closeAsync() {
            if (closed) {
                return;
            }
            closed = true;
            Thread closer = new Thread(() -> {
                try {
                    stream.close();
                } catch (IOException | RuntimeException ignored) {
                    // The connection may already be gone.
                }
                try {
                    connection.close();
                } catch (IOException | RuntimeException ignored) {
                    // Best effort.
                }
            }, "scrcpy-adb-close");
            closer.setDaemon(true);
            closer.start();
        }

        private void readLoop() {
            try {
                while (!closed) {
                    byte[] data = stream.read();
                    if (data == null || data.length == 0) {
                        continue;
                    }
                    String text = new String(data, StandardCharsets.UTF_8);
                    output.append(text);
                    if (serverStarted) {
                        Log.i(TAG, "server: " + text.trim());
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                // The shell was closed: either by us or because the server exited.
            } finally {
                output.close();
            }
        }

        private void write(String line) throws IOException, InterruptedException {
            // AdbStream.write(String) appends a NUL byte, which the tty would receive too.
            stream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }

        private String run(String command, Pattern marker, String failure) throws IOException,
                InterruptedException, LaunchException {
            write(command);
            String result = output.await(marker, SHELL_TIMEOUT_MS);
            if (result == null) {
                if (output.isClosed()) {
                    throw new LaunchException("The target closed the ADB shell. " + failure);
                }
                throw new LaunchException("The target's ADB shell stopped responding. " + failure);
            }
            return result;
        }

        void prepareShell() throws IOException, InterruptedException, LaunchException {
            // Disable echo and line editing (when available) so long upload lines are not re-rendered,
            // then wait for the marker. The quotes keep the echoed command from matching the marker.
            run("stty -echo 2>/dev/null; set +o emacs 2>/dev/null; set +o vi 2>/dev/null; echo SCRCPY_RE\"\"ADY",
                    READY, "Could not open a shell.");
        }

        void installServer(byte[] server, String expectedMd5) throws IOException, InterruptedException,
                LaunchException {
            if (expectedMd5 != null) {
                String installed = run("h=$(md5sum " + SERVER_PATH + " 2>/dev/null); echo SCRCPY_HASH_${h%% *}_END",
                        HASH, "Could not inspect the installed server.");
                if (expectedMd5.equalsIgnoreCase(installed)) {
                    Log.i(TAG, "scrcpy server already installed; skipping upload");
                    return;
                }
            }

            long started = System.currentTimeMillis();
            byte[] encoded = Base64.encode(server, Base64.NO_WRAP);
            run("rm -f " + UPLOAD_PATH + "; echo SCRCPY_RM\"\"_OK_", Pattern.compile("SCRCPY_RM_OK_"),
                    "Could not write to /data/local/tmp.");
            int chunk = 0;
            for (int offset = 0; offset < encoded.length; offset += UPLOAD_CHUNK_CHARS) {
                int length = Math.min(UPLOAD_CHUNK_CHARS, encoded.length - offset);
                String part = new String(encoded, offset, length, StandardCharsets.US_ASCII);
                run("echo " + part + " >> " + UPLOAD_PATH + "; echo SCRCPY_C\"\"_" + chunk + "_",
                        Pattern.compile("SCRCPY_C_" + chunk + "_"), "The server upload was interrupted.");
                chunk++;
            }
            String installed = run("base64 -d < " + UPLOAD_PATH + " > " + SERVER_PATH + "; rm -f " + UPLOAD_PATH
                            + "; h=$(md5sum " + SERVER_PATH + " 2>/dev/null); echo SCRCPY_HASH_${h%% *}_END",
                    HASH, "Could not unpack the server.");
            if (expectedMd5 != null && !installed.isEmpty() && !expectedMd5.equalsIgnoreCase(installed)) {
                throw new LaunchException("The scrcpy server was corrupted while uploading. Please try again.");
            }
            Log.i(TAG, "Uploaded scrcpy server in " + chunk + " chunks, "
                    + (System.currentTimeMillis() - started) + " ms");
        }

        void startServer(String command) throws IOException, InterruptedException {
            output.clearPending();
            serverStarted = true;
            // exec replaces the shell: when the server exits the ADB stream closes, and hanging
            // up the shell stops the server.
            write("exec " + command);
        }
    }
}
