package com.lemonrsh.phoneshare.decoder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AudioDecoderTest {
    @Test
    public void wrapsFlacStreamInfo() {
        byte[] streamInfo = new byte[34];
        byte[] csd = AudioDecoder.flacCsd(streamInfo);
        assertEquals(42, csd.length);
        assertEquals('f', csd[0]);
        assertEquals('C', csd[3]);
        assertEquals((byte) 0x80, csd[4]);
        assertEquals(34, csd[7]);
    }

    @Test
    public void readsOpusPreSkip() {
        byte[] head = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 2, 0x38, 0x01, (byte) 0x80, (byte) 0xbb, 0, 0, 0, 0, 0};
        // 0x0138 = 312 samples at 48 kHz = 6.5 ms
        assertEquals(6_500_000L, AudioDecoder.opusPreSkipNs(head));
    }
}
