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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    static File probe(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libgoprobe.so");
    }

    /** Environment every cloudflared/probe run gets. */
    private Map<String, String> baseEnvironment() {
        File home = new File(context.getFilesDir(), "cloudflared");
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        Map<String, String> env = new HashMap<>();
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts");
        env.put("SCRCPY_DNS", dnsServers(context));
        return env;
    }

    static final class RunResult {
        final int code;
        final String output;
        final boolean timedOut;

        RunResult(int code, String output, boolean timedOut) {
            this.code = code;
            this.output = output;
            this.timedOut = timedOut;
        }

        boolean ok() {
            return code == 0 && !timedOut;
        }
    }

    /** Runs a short command, logging its output under "selftest". Never throws. */
    private RunResult run(String label, List<String> command, Map<String, String> env, long timeoutMs) {
        DiagnosticLog.i("selftest", "$ " + label);
        final StringBuilder output = new StringBuilder();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(context.getFilesDir());
            builder.environment().putAll(env);
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            DiagnosticLog.i("selftest", "  could not start: " + e);
            return new RunResult(-1, String.valueOf(e), false);
        }
        final Process started = process;
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    DiagnosticLog.i("selftest", "  " + line);
                    synchronized (output) {
                        if (output.length() < 8000) {
                            output.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // ended
            }
        }, "selftest-reader");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + timeoutMs;
        Integer code = null;
        while (code == null && System.currentTimeMillis() < deadline) {
            try {
                code = started.exitValue();
            } catch (IllegalThreadStateException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
        boolean timedOut = code == null;
        if (timedOut) {
            started.destroy();
            code = -1;
        }
        try {
            reader.join(1000);
        } catch (InterruptedException ignored) {
            // ignore
        }
        DiagnosticLog.i("selftest", "  → " + (timedOut ? "timed out after " + timeoutMs + " ms" : "exit code " + code + describeExit(code)));
        synchronized (output) {
            return new RunResult(code, output.toString(), timedOut);
        }
    }

    /**
     * Diagnoses why cloudflared does or does not start on this phone and returns the extra
     * environment variables that made {@code cloudflared --version} work, or null if nothing did.
     * Everything goes to the Connection log under "selftest".
     */
    Map<String, String> selfTest() {
        File binary = binary(context);
        File probe = probe(context);
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        DiagnosticLog.i("selftest", "----- cloudflared self-test -----");
        DiagnosticLog.i("selftest", "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + ", hardware " + Build.HARDWARE
                + ", kernel " + System.getProperty("os.version"));
        DiagnosticLog.i("selftest", "ABIs " + Arrays.toString(Build.SUPPORTED_ABIS) + ", app library folder " + libDir);
        try {
            DiagnosticLog.i("selftest", "Page size " + android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE));
        } catch (RuntimeException ignored) {
            // ignore
        }
        DiagnosticLog.i("selftest", "cloudflared " + binary.length() + " bytes (executable " + binary.canExecute()
                + "), goprobe " + probe.length() + " bytes");
        Map<String, String> env = baseEnvironment();

        run("shell", Arrays.asList("/system/bin/sh", "-c",
                "echo shell ok; id; uname -a; getprop ro.product.cpu.abilist; ulimit -a 2>&1 | head -n 20;"
                        + " grep -m 5 -iE 'features|CPU part|CPU architecture|Hardware' /proc/cpuinfo;"
                        + " ls -l " + libDir), env, 10_000);

        if (probe.isFile()) {
            RunResult offline = run("goprobe --offline", Arrays.asList(probe.getAbsolutePath(), "--offline"), env, 15_000);
            if (offline.ok()) {
                run("goprobe (DNS + HTTPS)", Arrays.asList(probe.getAbsolutePath()), env, 30_000);
            }
        }

        List<Map<String, String>> variants = new ArrayList<>();
        variants.add(new HashMap<String, String>());
        Map<String, String> noPreempt = new HashMap<>();
        noPreempt.put("GODEBUG", "asyncpreemptoff=1");
        variants.add(noPreempt);
        Map<String, String> single = new HashMap<>(noPreempt);
        single.put("GOMAXPROCS", "1");
        variants.add(single);
        for (Map<String, String> variant : variants) {
            Map<String, String> runEnv = new HashMap<>(env);
            runEnv.putAll(variant);
            RunResult result = run("cloudflared --version " + variant, Arrays.asList(binary.getAbsolutePath(), "--version"),
                    runEnv, 20_000);
            if (result.ok()) {
                DiagnosticLog.i("selftest", "cloudflared starts with " + (variant.isEmpty() ? "the default settings" : variant));
                return variant;
            }
        }
        // Let the shell report how it died (e.g. "Segmentation fault").
        run("sh -c cloudflared --version", Arrays.asList("/system/bin/sh", "-c",
                "\"$0\" --version; echo \"shell: exit $?\"", binary.getAbsolutePath()), env, 20_000);
        DiagnosticLog.i("selftest", "cloudflared does not start on this phone");
        return null;
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
        Map<String, String> extra = selfTest();
        if (extra == null) {
            throw new IOException("cloudflared crashes when it starts on this phone. Please send the Connection log "
                    + "(Logs → Connection log, lines tagged selftest).");
        }
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "tunnel", "--no-autoupdate",
                "--url", "http://127.0.0.1:" + localPort);
        builder.directory(home);
        Map<String, String> env = baseEnvironment();
        env.putAll(extra);
        builder.environment().putAll(env);
        String dns = env.get("SCRCPY_DNS");
        DiagnosticLog.i("cloudflared", "DNS servers: " + (dns == null || dns.isEmpty() ? "(none found, using 1.1.1.1 / 8.8.8.8)" : dns));
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
