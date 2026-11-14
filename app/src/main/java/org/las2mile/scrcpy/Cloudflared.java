package org.las2mile.scrcpy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Cloudflare's {@code cloudflared} connector (bundled as {@code libcloudflared.so}, so that
 * Android extracts it to the executable native-library folder) as a TryCloudflare quick tunnel:
 * no account, a new random trycloudflare.com address every time.
 *
 * <p>The binary is a static Linux build (see the CI workflow). It gets the phone's DNS servers
 * in {@code SCRCPY_DNS} (tools/cloudflared/android_dns.go) and Android's CA certificates in
 * {@code SSL_CERT_DIR}.
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
        builder.environment().put("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts");
        String dns = dnsServers(context);
        builder.environment().put("SCRCPY_DNS", dns);
        DiagnosticLog.i("cloudflared", "DNS servers: " + (dns.isEmpty() ? "(none found, using 1.1.1.1 / 8.8.8.8)" : dns));
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
            DiagnosticLog.i("cloudflared", "Exited with code " + code + describeExit(code));
            listener.onExit(stopping ? null : "cloudflared stopped (exit code " + code + describeExit(code)
                    + "). See Logs → Connection log.");
        }, "cloudflared-log");
        reader.setDaemon(true);
        reader.start();
    }

    static String describeExit(int code) {
        if (code > 128 && code < 160) {
            int signal = code - 128;
            String name = signal == 11 ? "SIGSEGV, crashed" : signal == 9 ? "SIGKILL, killed by Android"
                    : signal == 6 ? "SIGABRT" : signal == 31 ? "SIGSYS, blocked system call" : "signal " + signal;
            return ": " + name;
        }
        return "";
    }

    /** The active network's DNS servers, comma separated (may be empty). */
    @SuppressWarnings("deprecation")
    static String dnsServers(Context context) {
        Set<String> servers = new LinkedHashSet<>();
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null) {
                Network[] networks;
                if (Build.VERSION.SDK_INT >= 23 && manager.getActiveNetwork() != null) {
                    networks = new Network[] {manager.getActiveNetwork()};
                } else {
                    networks = manager.getAllNetworks();
                }
                for (Network network : networks) {
                    LinkProperties properties = manager.getLinkProperties(network);
                    if (properties == null) {
                        continue;
                    }
                    for (InetAddress address : properties.getDnsServers()) {
                        String text = address.getHostAddress();
                        int scope = text.indexOf('%');
                        if (scope >= 0) {
                            text = text.substring(0, scope);
                        }
                        servers.add(text.indexOf(':') >= 0 ? "[" + text + "]:53" : text + ":53");
                    }
                }
            }
        } catch (RuntimeException e) {
            DiagnosticLog.e("cloudflared", "Could not read the DNS servers", e);
        }
        StringBuilder out = new StringBuilder();
        for (String server : servers) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(server);
        }
        return out.toString();
    }

    synchronized void stop() {
        stopping = true;
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
