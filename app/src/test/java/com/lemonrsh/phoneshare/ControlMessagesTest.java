package com.lemonrsh.phoneshare;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ControlMessagesTest {
    @Test
    public void encodesTouchEventLikeTheServerReadsIt() {
        byte[] message = ControlMessages.touch(ControlMessages.ACTION_MOVE, 3, 100, 200, 1080, 1920, 1f, 0, 0);
        assertEquals(ControlMessages.TOUCH_EVENT_SIZE, message.length);
        ByteBuffer buffer = ByteBuffer.wrap(message);
        assertEquals(ControlMessages.TYPE_INJECT_TOUCH_EVENT, buffer.get());
        assertEquals(ControlMessages.ACTION_MOVE, buffer.get());
        assertEquals(3L, buffer.getLong());
        assertEquals(100, buffer.getInt());
        assertEquals(200, buffer.getInt());
        assertEquals(1080, buffer.getShort() & 0xffff);
        assertEquals(1920, buffer.getShort() & 0xffff);
        assertEquals(0xffff, buffer.getShort() & 0xffff);
        assertEquals(0, buffer.getInt());
        assertEquals(0, buffer.getInt());
        assertEquals(0, buffer.remaining());
    }

    @Test
    public void encodesKeycode() {
        byte[] message = ControlMessages.keycode(ControlMessages.KEY_ACTION_UP, 187, 0, 0);
        assertArrayEquals(new byte[] {0, 1, 0, 0, 0, (byte) 187, 0, 0, 0, 0, 0, 0, 0, 0}, message);
    }

    @Test
    public void encodesSimpleMessages() {
        assertArrayEquals(new byte[] {10, 0}, ControlMessages.setDisplayPower(false));
        assertArrayEquals(new byte[] {10, 1}, ControlMessages.setDisplayPower(true));
        assertArrayEquals(new byte[] {17}, ControlMessages.resetVideo());
    }

    @Test
    public void pressureFixedPoint() {
        assertEquals(0, ControlMessages.toU16FixedPoint(0f));
        assertEquals(0, ControlMessages.toU16FixedPoint(-1f));
        assertEquals(0, ControlMessages.toU16FixedPoint(Float.NaN));
        assertEquals(0x8000, ControlMessages.toU16FixedPoint(0.5f));
        assertEquals(0xffff, ControlMessages.toU16FixedPoint(1f));
        assertEquals(0xffff, ControlMessages.toU16FixedPoint(2f));
    }

    @Test
    public void encodesSetClipboard() {
        byte[] message = ControlMessages.setClipboard(7, "héllo", false);
        ByteBuffer buffer = ByteBuffer.wrap(message);
        assertEquals(ControlMessages.TYPE_SET_CLIPBOARD, buffer.get());
        assertEquals(7L, buffer.getLong());
        assertEquals(0, buffer.get());
        assertEquals(6, buffer.getInt()); // é is two bytes in UTF-8
        assertEquals(6, buffer.remaining());
    }

    @Test
    public void encodesStartApp() {
        byte[] message = ControlMessages.startApp("com.example");
        assertEquals(ControlMessages.TYPE_START_APP, message[0]);
        assertEquals(11, message[1]);
        assertEquals(13, message.length);
    }

    @Test
    public void truncatesUtf8AtCharacterBoundary() {
        // "aé" is 61 C3 A9: cutting at 2 bytes must not split é.
        assertEquals(1, ControlMessages.truncateUtf8("aé", 2).length);
        assertEquals(3, ControlMessages.truncateUtf8("aé", 3).length);
    }
}
