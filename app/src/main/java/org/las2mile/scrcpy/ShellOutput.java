package org.las2mile.scrcpy;

import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe accumulator for the output of an interactive ADB shell. A reader
 * thread appends data; the launcher waits for marker patterns with a deadline
 * instead of guessing where the shell prompt ends. Pure Java for unit tests.
 */
final class ShellOutput {
    private static final int MAX_PENDING_CHARS = 64 * 1024;
    private static final int MAX_RECENT_LINES = 40;

    private final StringBuilder pending = new StringBuilder();
    private final ArrayDeque<String> recentLines = new ArrayDeque<>();
    private final StringBuilder currentLine = new StringBuilder();
    private boolean closed;

    synchronized void append(String text) {
        pending.append(text);
        if (pending.length() > MAX_PENDING_CHARS) {
            pending.delete(0, pending.length() - MAX_PENDING_CHARS);
        }
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == '\n') {
                pushLine();
            } else if (c != '\r' && c != 0) {
                if (currentLine.length() < 512) {
                    currentLine.append(c);
                }
            }
        }
        notifyAll();
    }

    synchronized void close() {
        closed = true;
        pushLine();
        notifyAll();
    }

    synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Waits until {@code pattern} matches the unread output, consumes the output
     * up to the end of the match and returns the matcher's group 1 (or the whole
     * match when the pattern has no group). Returns {@code null} on timeout or
     * when the shell closes first.
     */
    synchronized String await(Pattern pattern, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Matcher matcher = pattern.matcher(pending);
            if (matcher.find()) {
                String result = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                pending.delete(0, matcher.end());
                return result == null ? "" : result;
            }
            if (closed) {
                return null;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            wait(remaining);
        }
    }

    /** Discards unread output that is no longer needed. */
    synchronized void clearPending() {
        pending.setLength(0);
    }

    /** The last lines printed by the shell, for diagnostics. */
    synchronized String recentText(int maxLines) {
        StringBuilder builder = new StringBuilder();
        int skip = Math.max(0, recentLines.size() - maxLines);
        int index = 0;
        for (String line : recentLines) {
            if (index++ < skip) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        if (currentLine.length() > 0 && maxLines > 0) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(currentLine);
        }
        return builder.toString();
    }

    /** Most recent server error line, if the server printed one. */
    synchronized String lastErrorLine() {
        String found = null;
        for (String line : recentLines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[server] ERROR") || trimmed.startsWith("ERROR")
                    || trimmed.contains("Exception:") || trimmed.startsWith("Aborted")) {
                found = trimmed;
            }
        }
        return found;
    }

    private void pushLine() {
        if (currentLine.length() == 0) {
            return;
        }
        recentLines.addLast(currentLine.toString());
        currentLine.setLength(0);
        while (recentLines.size() > MAX_RECENT_LINES) {
            recentLines.removeFirst();
        }
    }
}
