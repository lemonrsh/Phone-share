package org.las2mile.scrcpy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

/**
 * Two-way clipboard sync for one session (scrcpy clipboard_autosync): text
 * copied on the target lands in this phone's clipboard and vice versa. Must be
 * used on the main thread. Android 10+ only lets the app read the clipboard
 * while it is in front, so phone-to-target sync works in full-screen mode.
 */
final class ClipboardSync implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardSync";

    interface Target {
        ScrcpyClient client();
    }

    private final ClipboardManager clipboard;
    private final Target target;
    private String lastText;
    private boolean started;

    ClipboardSync(Context context, Target target) {
        this.clipboard = (ClipboardManager) context.getApplicationContext().getSystemService(Context.CLIPBOARD_SERVICE);
        this.target = target;
    }

    void start() {
        if (started || clipboard == null) {
            return;
        }
        started = true;
        lastText = null;
        try {
            clipboard.addPrimaryClipChangedListener(this);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not watch the clipboard", e);
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        try {
            clipboard.removePrimaryClipChangedListener(this);
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    /** The target's clipboard changed: copy it here (without echoing it back). */
    void onDeviceClipboard(String text) {
        if (!started || text == null || text.equals(lastText)) {
            return;
        }
        lastText = text;
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("scrcpy", text));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not set the clipboard", e);
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        if (!started) {
            return;
        }
        String text;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return;
            }
            CharSequence value = clip.getItemAt(0).coerceToText(null);
            text = value == null ? null : value.toString();
        } catch (RuntimeException e) {
            // Background clipboard access is denied on Android 10+.
            return;
        }
        if (text == null || text.equals(lastText)) {
            return;
        }
        lastText = text;
        ScrcpyClient client = target.client();
        if (client != null) {
            client.setClipboard(text);
        }
    }
}
