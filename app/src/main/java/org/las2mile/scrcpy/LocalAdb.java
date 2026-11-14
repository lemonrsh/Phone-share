package org.las2mile.scrcpy;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "ADB over Wi-Fi on port 5555" for this phone, so that it can itself be
 * mirrored from another device. Turning it on needs root (setprop + adbd
 * restart); without root, Android only allows it from a computer
 * ({@code adb tcpip 5555}). All methods block: call them off the main thread.
 */
final class LocalAdb {
    static final int PORT = 5555;
    private static final int CHECK_TIMEOUT_MS = 600;
    private static final long ROOT_TIMEOUT_MS = 30_000;

    static final class Result {
        final boolean ok;
        final boolean noRoot;
        final String output;

        Result(boolean ok, boolean noRoot, String output) {
            this.ok = ok;
            this.noRoot = noRoot;
            this.output = output;
        }
    }

    private LocalAdb() {
    }

    /** Whether adbd accepts TCP connections on port 5555 of this phone. */
    static boolean isListening() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), CHECK_TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
        }
    }

    /** This phone's IPv4 addresses (Wi-Fi, hotspot, VPN such as Tailscale), best first. */
    static List<String> localAddresses() {
        List<String> preferred = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName() == null ? "" : nif.getName();
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String text = address.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                        preferred.add(text);
                    } else if (!name.startsWith("rmnet") && !name.startsWith("ccmni")) {
                        // Mobile data addresses are not reachable from other devices.
                        others.add(text + " (" + name + ")");
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // No interfaces: return what we have.
        }
        preferred.addAll(others);
        return preferred;
    }

    static String enableCommand(boolean persist) {
        return (persist ? "setprop persist.adb.tcp.port " + PORT + "; " : "")
                + "setprop service.adb.tcp.port " + PORT + "; stop adbd; start adbd";
    }

    static String disableCommand() {
        return "setprop persist.adb.tcp.port ''; setprop service.adb.tcp.port -1; stop adbd; start adbd";
    }

    /** Turns ADB over TCP on (root). Waits until the port answers. */
    static Result enable(boolean persist) {
        Result result = runRoot(enableCommand(persist));
        if (!result.ok) {
            return result;
        }
        long deadline = SystemClock.uptimeMillis() + 8_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isListening()) {
                return result;
            }
            SystemClock.sleep(400);
        }
        return new Result(false, false, "adbd restarted but port " + PORT + " is not open."
                + (result.output.isEmpty() ? "" : "\n" + result.output));
    }

    static Result disable() {
        return runRoot(disableCommand());
    }

    // ---------------------------------------------------------------- one-tap root setup

    /** One step of {@link #rootSetup}: a label for the user and the root shell command. */
    static final class SetupStep {
        final String label;
        final String command;

        SetupStep(String label, String command) {
            this.label = label;
            this.command = command;
        }
    }

    /** Outcome of {@link #rootSetup}: per-step success, or {@link Result#noRoot}. */
    static final class SetupResult {
        final Result result;
        final List<SetupStep> steps;
        final boolean[] ok;
        final boolean adbListening;

        SetupResult(Result result, List<SetupStep> steps, boolean[] ok, boolean adbListening) {
            this.result = result;
            this.steps = steps;
            this.ok = ok;
            this.adbListening = adbListening;
        }
    }

    /**
     * Everything this app can use, set up with root: developer options and ADB on, ADB over
     * Wi-Fi on port 5555 (kept after reboots), floating-window and notification permissions,
     * no battery restrictions. ADB authorizations are never removed or bypassed: the other
     * phone still needs the user's "Allow" the first time.
     */
    static List<SetupStep> rootSetupSteps(String packageName, int sdk) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9._]+")) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        List<SetupStep> steps = new ArrayList<>();
        steps.add(new SetupStep("Developer options on", "settings put global development_settings_enabled 1"));
        steps.add(new SetupStep("USB / ADB debugging on", "settings put global adb_enabled 1"));
        if (sdk >= 30) {
            // Android 11+ forgets ADB authorizations after 7 days without use; 0 = never.
            steps.add(new SetupStep("Remember allowed phones (no 7-day expiry)",
                    "settings put global adb_allowed_connection_time 0"));
        }
        if (sdk >= 23) {
            steps.add(new SetupStep("Floating window (display over other apps)",
                    "appops set " + packageName + " SYSTEM_ALERT_WINDOW allow"));
        }
        if (sdk >= 33) {
            steps.add(new SetupStep("Notifications", "pm grant " + packageName + " android.permission.POST_NOTIFICATIONS"));
        }
        if (sdk >= 23) {
            steps.add(new SetupStep("No battery restrictions", "dumpsys deviceidle whitelist +" + packageName));
        }
        if (sdk >= 28) {
            steps.add(new SetupStep("Allowed to run in the background",
                    "appops set " + packageName + " RUN_IN_BACKGROUND allow; appops set " + packageName
                            + " RUN_ANY_IN_BACKGROUND allow"));
        } else if (sdk >= 24) {
            steps.add(new SetupStep("Allowed to run in the background",
                    "appops set " + packageName + " RUN_IN_BACKGROUND allow"));
        }
        // Last: restarting adbd briefly drops ADB connections.
        steps.add(new SetupStep("ADB over Wi-Fi on port " + PORT + " (kept after reboot)", enableCommand(true)));
        return steps;
    }

    /** One root shell script that runs every step and prints STEP:&lt;index&gt;:OK or FAIL for each. */
    static String rootSetupScript(List<SetupStep> steps) {
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < steps.size(); ++i) {
            script.append("if ( ").append(steps.get(i).command).append(" ) >/dev/null 2>&1; then echo STEP:")
                    .append(i).append(":OK; else echo STEP:").append(i).append(":FAIL; fi; ");
        }
        return script.toString().trim();
    }

    /** Parses the STEP lines printed by {@link #rootSetupScript}. Missing steps count as failed. */
    static boolean[] parseSetupOutput(String output, int count) {
        boolean[] ok = new boolean[count];
        if (output == null) {
            return ok;
        }
        for (String line : output.split("\\r?\\n")) {
            String[] parts = line.trim().split(":");
            if (parts.length == 3 && "STEP".equals(parts[0])) {
                try {
                    int index = Integer.parseInt(parts[1]);
                    if (index >= 0 && index < count) {
                        ok[index] = "OK".equals(parts[2]);
                    }
                } catch (NumberFormatException ignored) {
                    // Not one of ours.
                }
            }
        }
        return ok;
    }

    /** Runs every setup step with root (one prompt), then waits for port 5555. Blocks. */
    static SetupResult rootSetup(String packageName, int sdk) {
        List<SetupStep> steps = rootSetupSteps(packageName, sdk);
        Result result = runRoot(rootSetupScript(steps));
        boolean[] ok = parseSetupOutput(result.output, steps.size());
        if (result.noRoot) {
            return new SetupResult(result, steps, ok, isListening());
        }
        long deadline = SystemClock.uptimeMillis() + 8_000;
        boolean listening = isListening();
        while (!listening && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(400);
            listening = isListening();
        }
        return new SetupResult(result, steps, ok, listening);
    }

    /** Runs {@code command} with su. {@link Result#noRoot} is set when su is missing or denied. */
    static Result runRoot(String command) {
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (IOException | RuntimeException e) {
            return new Result(false, true, "su is not available (this phone is not rooted).");
        }
        final Process running = process;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try (InputStream input = running.getInputStream()) {
                int length;
                while ((length = input.read(buffer)) != -1) {
                    synchronized (output) {
                        if (output.size() < 16 * 1024) {
                            output.write(buffer, 0, length);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process ended.
            }
        }, "local-adb-su");
        reader.setDaemon(true);
        reader.start();

        // The root manager may show a grant prompt: give the user time to answer.
        long deadline = SystemClock.uptimeMillis() + ROOT_TIMEOUT_MS;
        Integer exit = null;
        while (exit == null && SystemClock.uptimeMillis() < deadline) {
            try {
                exit = running.exitValue();
            } catch (IllegalThreadStateException e) {
                SystemClock.sleep(150);
            }
        }
        if (exit == null) {
            running.destroy();
            return new Result(false, true, "No answer from su (root permission prompt timed out).");
        }
        try {
            reader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String text;
        synchronized (output) {
            text = output.toString().trim();
        }
        if (exit != 0) {
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            boolean denied = exit == 1 && (text.isEmpty() || lower.contains("denied") || lower.contains("not allowed")
                    || lower.contains("permission"));
            return new Result(false, denied, denied ? "Root permission was denied." + (text.isEmpty() ? "" : "\n" + text)
                    : "Command failed (exit " + exit + ")" + (text.isEmpty() ? "" : ":\n" + text));
        }
        return new Result(true, false, text);
    }
}
