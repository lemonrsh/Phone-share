package com.lemonrsh.phoneshare;

/** Small performance helpers shared by the streaming threads. */
public final class Perf {
    private Perf() {
    }

    /**
     * Raises the calling thread's Linux priority (e.g. {@code Process.THREAD_PRIORITY_URGENT_AUDIO}
     * for audio, {@code THREAD_PRIORITY_URGENT_DISPLAY} for video) so that frames and sound are
     * not delayed by background work. Best effort: ignored where not allowed (or in unit tests).
     */
    public static void boostThread(int priority) {
        try {
            android.os.Process.setThreadPriority(priority);
        } catch (RuntimeException | LinkageError ignored) {
            // not allowed, or not running on Android
        }
    }
}
