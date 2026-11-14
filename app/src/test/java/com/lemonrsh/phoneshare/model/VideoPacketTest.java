package com.lemonrsh.phoneshare.model;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;

public class VideoPacketTest {
    @Test
    public void splitsAnnexBConfigIntoSpsAndPps() {
        byte[] config = new byte[] {
                0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
                0, 0, 1, 0x68, (byte) 0xce, 6, (byte) 0xe2
        };

        VideoPacket.StreamSettings settings = VideoPacket.getStreamSettings(config);
        assertArrayEquals(new byte[] {0, 0, 0, 1, 0x67, 0x42, 0, 0x1e}, remainingBytes(settings.sps));
        assertArrayEquals(new byte[] {0, 0, 1, 0x68, (byte) 0xce, 6, (byte) 0xe2}, remainingBytes(settings.pps));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsConfigWithoutPps() {
        VideoPacket.getStreamSettings(new byte[] {0, 0, 0, 1, 0x67, 0x42});
    }

    private static byte[] remainingBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
