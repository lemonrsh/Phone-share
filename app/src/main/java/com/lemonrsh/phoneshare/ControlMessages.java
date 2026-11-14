package com.lemonrsh.phoneshare;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Serializes the scrcpy v5.0.1 control messages used by this viewer. The
 * layouts mirror {@code server/.../control/ControlMessageReader.java}.
 * Pure Java so that it can be covered by JVM unit tests.
 */
public final class ControlMessages {
    public static final int TYPE_INJECT_KEYCODE = 0;
    public static final int TYPE_INJECT_TOUCH_EVENT = 2;
    public static final int TYPE_SET_CLIPBOARD = 9;
    public static final int TYPE_SET_DISPLAY_POWER = 10;
    public static final int TYPE_START_APP = 16;
    public static final int TYPE_RESET_VIDEO = 17;

    /** Device message types (server to viewer, on the control socket). */
    public static final int DEVICE_MSG_CLIPBOARD = 0;
    public static final int DEVICE_MSG_ACK_CLIPBOARD = 1;
    public static final int DEVICE_MSG_UHID_OUTPUT = 2;

    /** Same limit as the upstream client (the server rejects larger messages). */
    public static final int CLIPBOARD_TEXT_MAX_LENGTH = (1 << 18) - 14;

    /** android.view.MotionEvent action values understood by the server. */
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;

    /** android.view.KeyEvent action values. */
    public static final int KEY_ACTION_DOWN = 0;
    public static final int KEY_ACTION_UP = 1;

    public static final int TOUCH_EVENT_SIZE = 32;
    public static final int KEYCODE_SIZE = 14;

    private ControlMessages() {
    }

    public static byte[] touch(int action, long pointerId, int x, int y, int screenWidth, int screenHeight,
                               float pressure, int actionButton, int buttons) {
        ByteBuffer buffer = ByteBuffer.allocate(TOUCH_EVENT_SIZE);
        buffer.put((byte) TYPE_INJECT_TOUCH_EVENT);
        buffer.put((byte) action);
        buffer.putLong(pointerId);
        buffer.putInt(x);
        buffer.putInt(y);
        buffer.putShort((short) screenWidth);
        buffer.putShort((short) screenHeight);
        buffer.putShort((short) toU16FixedPoint(pressure));
        buffer.putInt(actionButton);
        buffer.putInt(buttons);
        return buffer.array();
    }

    public static byte[] keycode(int action, int keycode, int repeat, int metaState) {
        ByteBuffer buffer = ByteBuffer.allocate(KEYCODE_SIZE);
        buffer.put((byte) TYPE_INJECT_KEYCODE);
        buffer.put((byte) action);
        buffer.putInt(keycode);
        buffer.putInt(repeat);
        buffer.putInt(metaState);
        return buffer.array();
    }

    public static byte[] setDisplayPower(boolean on) {
        return new byte[] {(byte) TYPE_SET_DISPLAY_POWER, (byte) (on ? 1 : 0)};
    }

    /** Sets the target's clipboard (without pasting). Text longer than the protocol limit is truncated. */
    public static byte[] setClipboard(long sequence, String text, boolean paste) {
        byte[] raw = truncateUtf8(text == null ? "" : text, CLIPBOARD_TEXT_MAX_LENGTH);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8 + 1 + 4 + raw.length);
        buffer.put((byte) TYPE_SET_CLIPBOARD);
        buffer.putLong(sequence);
        buffer.put((byte) (paste ? 1 : 0));
        buffer.putInt(raw.length);
        buffer.put(raw);
        return buffer.array();
    }

    /** Starts an app on the target, like scrcpy --start-app (name length is a single byte). */
    public static byte[] startApp(String name) {
        byte[] raw = truncateUtf8(name == null ? "" : name, 255);
        ByteBuffer buffer = ByteBuffer.allocate(2 + raw.length);
        buffer.put((byte) TYPE_START_APP);
        buffer.put((byte) raw.length);
        buffer.put(raw);
        return buffer.array();
    }

    /** UTF-8 bytes of {@code text}, cut at a character boundary to at most {@code maxBytes}. */
    static byte[] truncateUtf8(String text, int maxBytes) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return raw;
        }
        int end = maxBytes;
        // Do not cut inside a multi-byte sequence (continuation bytes are 10xxxxxx).
        while (end > 0 && (raw[end] & 0xC0) == 0x80) {
            --end;
        }
        byte[] result = new byte[end];
        System.arraycopy(raw, 0, result, 0, end);
        return result;
    }

    public static byte[] resetVideo() {
        return new byte[] {(byte) TYPE_RESET_VIDEO};
    }

    /** Same encoding as the upstream client: 1.0 maps to 0xffff. */
    static int toU16FixedPoint(float value) {
        if (!(value > 0f)) {
            return 0;
        }
        if (value >= 1f) {
            return 0xffff;
        }
        return Math.min(0xffff, (int) (value * 65536f));
    }
}
