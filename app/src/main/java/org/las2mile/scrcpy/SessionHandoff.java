package org.las2mile.scrcpy;

/**
 * Carries a live session from the full-screen viewer to the floating window
 * (when the user presses Home or opens Recents), so mirroring continues
 * without reconnecting. Main thread only.
 */
final class SessionHandoff {
    static final class Item {
        final ScrcpyClient client;
        final ScrcpySessionOptions options;
        final String host;

        Item(ScrcpyClient client, ScrcpySessionOptions options, String host) {
            this.client = client;
            this.options = options;
            this.host = host;
        }
    }

    private static Item pending;

    private SessionHandoff() {
    }

    static void put(ScrcpyClient client, ScrcpySessionOptions options, String host) {
        Item previous = pending;
        pending = new Item(client, options, host);
        if (previous != null && previous.client != client) {
            previous.client.stop();
        }
    }

    static Item take() {
        Item item = pending;
        pending = null;
        return item;
    }
}
