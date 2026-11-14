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
