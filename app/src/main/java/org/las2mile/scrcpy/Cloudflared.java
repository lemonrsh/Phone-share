package org.las2mile.scrcpy;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Cloudflare's {@code cloudflared} connector (bundled as {@code libcloudflared.so}, built
 * for Android by CI, so that Android extracts it to the executable native-library folder) as a
 * TryCloudflare quick tunnel: no account, a new random trycloudflare.com address every time.
 */
final class Cloudflared {
    private static final Pattern URL = Pattern.compile("https://([a-z0-9-]+\\.trycloudflare\\.com)");

    interface Listener {
        void onAddress(String host);

        void onOnline();

        void onExit(String reason);
    }

    private final Context context;
    private Process process;
    private volatile boolean stopping;

    Cloudflared(Context context) {
        this.context = context.getApplicationContext();
    }

    static File binary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
    }

    static boolean isBundled(Context context) {
        return binary(context).isFile();
    }

    /** Extracts the trycloudflare host name from a cloudflared log line, or returns null. */
    static String findAddress(String line) {
        Matcher matcher = URL.matcher(line);
        while (matcher.find()) {
            String host = matcher.group(1);
            if (!host.startsWith("api.")) {
                return host;
            }
        }
        return null;
    }

    synchronized void start(int localPort, final Listener listener) throws IOException {
        File binary = binary(context);
        if (!binary.isFile()) {
            throw new IOException("cloudflared is not included in this build of the app.");
        }
        File home = new File(context.getFilesDir(), "cloudflared");
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("Cannot create " + home);
        }
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "tunnel", "--no-autoupdate",
                "--url", "http://127.0.0.1:" + localPort);
        builder.directory(home);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        builder.redirectErrorStream(true);
        DiagnosticLog.i("cloudflared", "Starting quick tunnel to http://127.0.0.1:" + localPort);
        stopping = false;
        final Process started = builder.start();
        process = started;
        Thread reader = new Thread(() -> {
            boolean announced = false;
            boolean online = false;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    DiagnosticLog.i("cloudflared", line);
                    if (!announced) {
                        String host = findAddress(line);
                        if (host != null) {
                            announced = true;
                            listener.onAddress(host);
                        }
                    }
                    if (!online && line.contains("Registered tunnel connection")) {
                        online = true;
                        listener.onOnline();
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
            int code;
            try {
                code = started.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            DiagnosticLog.i("cloudflared", "Exited with code " + code);
            listener.onExit(stopping ? null : "cloudflared stopped (exit code " + code + "). See Logs → Connection log.");
        }, "cloudflared-log");
        reader.setDaemon(true);
        reader.start();
    }

    synchronized void stop() {
        stopping = true;
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
