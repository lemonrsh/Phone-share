package com.lemonrsh.phoneshare;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WsFramesTest {
    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    @Test
    public void writerProducesTheSameBytesAsBefore() throws IOException {
        for (int size : new int[] {0, 1, 125, 126, 65535, 65536, 200_000}) {
            for (boolean mask : new boolean[] {false, true}) {
                byte[] data = payload(size);
                ByteArrayOutputStream oldOut = new ByteArrayOutputStream();
                WsFrames.writeUnbuffered(oldOut, WsFrames.OP_BINARY, data, 0, size, mask, new Random(7));
                ByteArrayOutputStream newOut = new ByteArrayOutputStream();
                new WsFrames.Writer().write(newOut, WsFrames.OP_BINARY, data, 0, size, mask, new Random(7));
                assertArrayEquals("size " + size + " mask " + mask, oldOut.toByteArray(), newOut.toByteArray());
            }
        }
    }

    @Test
    public void readerReusesItsBufferAcrossFrames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WsFrames.Writer writer = new WsFrames.Writer();
        byte[] big = payload(70_000);
        byte[] small = payload(10);
        writer.write(out, WsFrames.OP_BINARY, big, 0, big.length, true, new Random(1));
        writer.write(out, WsFrames.OP_PING, small, 2, 5, false, new Random(2));
        writer.write(out, WsFrames.OP_BINARY, big, 100, 300, true, new Random(3));

        WsFrames.Reader reader = new WsFrames.Reader(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(reader.next());
        assertEquals(WsFrames.OP_BINARY, reader.opcode);
        assertArrayEquals(big, Arrays.copyOf(reader.payload, reader.length));
        byte[] buffer = reader.payload;

        assertTrue(reader.next());
        assertEquals(WsFrames.OP_PING, reader.opcode);
        assertArrayEquals(Arrays.copyOfRange(small, 2, 7), Arrays.copyOf(reader.payload, reader.length));
        assertTrue(buffer == reader.payload);

        assertTrue(reader.next());
        assertArrayEquals(Arrays.copyOfRange(big, 100, 400), Arrays.copyOf(reader.payload, reader.length));
        assertFalse(reader.next());
    }

    @Test
    public void readerAndOldReaderAgree() throws IOException {
        byte[] data = payload(1000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WsFrames.Writer().write(out, WsFrames.OP_TEXT, data, 0, data.length, true, new Random(9));
        WsFrames.Frame frame = WsFrames.read(new ByteArrayInputStream(out.toByteArray()));
        WsFrames.Reader reader = new WsFrames.Reader(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(reader.next());
        assertEquals(frame.opcode, reader.opcode);
        assertArrayEquals(frame.payload, Arrays.copyOf(reader.payload, reader.length));
    }
}
