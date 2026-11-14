package org.las2mile.scrcpy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-memory, thread-safe log of what the app did during connection attempts:
 * each step, every shell command and the raw shell/server output. Shown in
 * {@link LogActivity} so problems can be diagnosed without root or a PC.
 * Pure Java so that it can be unit tested.
 */
public final class DiagnosticLog {
    static final int MAX_LINES = 4_000;
    private static final int MAX_LINE_CHARS = 2_000;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final StringBuilder PARTIAL_SHELL_LINE = new StringBuilder();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DiagnosticLog() {
    }

    public static void i(String tag, String message) {
        add(tag, message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        add(tag, message + (throwable == null ? "" : "\n" + stackTrace(throwable)));
    }

    /** Raw output from the target's shell (may arrive in arbitrary pieces). */
    public static void shellOutput(String text) {
        synchronized (LOCK) {
            for (int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                if (c == '\n') {
                    flushShellLineLocked();
                } else if (c != '\r' && c != 0 && PARTIAL_SHELL_LINE.length() < MAX_LINE_CHARS) {
                    PARTIAL_SHELL_LINE.append(c);
                }
            }
        }
    }

    /** Writes out a pending partial shell line (e.g. a prompt or a last line without newline). */
    public static void flushShell() {
        synchronized (LOCK) {
            flushShellLineLocked();
        }
    }

    public static String snapshot() {
        synchronized (LOCK) {
            StringBuilder builder = new StringBuilder(LINES.size() * 60);
            for (String line : LINES) {
                builder.append(line).append('\n');
            }
            if (PARTIAL_SHELL_LINE.length() > 0) {
                builder.append(prefix("target")).append(PARTIAL_SHELL_LINE).append('\n');
            }
            return builder.toString();
        }
    }

    public static boolean isEmpty() {
        synchronized (LOCK) {
            return LINES.isEmpty() && PARTIAL_SHELL_LINE.length() == 0;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            PARTIAL_SHELL_LINE.setLength(0);
        }
    }

    private static void add(String tag, String message) {
        synchronized (LOCK) {
            flushShellLineLocked();
            String text = message == null ? "null" : message;
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; ++i) {
                String part = parts[i];
                if (part.length() > MAX_LINE_CHARS) {
                    part = part.substring(0, MAX_LINE_CHARS) + "…";
                }
                addLineLocked((i == 0 ? prefix(tag) : "    ") + part);
            }
        }
    }

    private static void flushShellLineLocked() {
        if (PARTIAL_SHELL_LINE.length() == 0) {
            return;
        }
        addLineLocked(prefix("target") + PARTIAL_SHELL_LINE);
        PARTIAL_SHELL_LINE.setLength(0);
    }

    private static void addLineLocked(String line) {
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
    }

    private static String prefix(String tag) {
        return TIME.format(new Date()) + " " + tag + ": ";
    }

    static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString().trim();
    }
}
