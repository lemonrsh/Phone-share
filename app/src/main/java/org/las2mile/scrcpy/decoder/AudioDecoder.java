package org.las2mile.scrcpy.decoder;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plays the scrcpy server audio stream: AAC, Opus and FLAC through MediaCodec,
 * raw PCM (16-bit, 48 kHz, stereo) directly into an AudioTrack.
 */
public final class AudioDecoder {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNEL_COUNT = 2;
    /** Opus seek pre-roll recommended by the spec (80 ms), in nanoseconds. */
    private static final long OPUS_SEEK_PREROLL_NS = 80_000_000L;

    private final int codecId;
    private final String mimeType;
    private MediaCodec codec;
    private AudioTrack audioTrack;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private byte[] pcmBuffer = new byte[0];

    public AudioDecoder(int codecId) {
        this.codecId = codecId;
        this.mimeType = Codecs.audioMime(codecId);
        if (codecId == Codecs.AUDIO_RAW) {
            // Raw streams have no configuration packet: the output format is fixed.
            configureAudioTrack(SAMPLE_RATE, CHANNEL_COUNT);
        }
    }

    public synchronized void configure(byte[] config) throws IOException {
        if (codecId == Codecs.AUDIO_RAW) {
            return;
        }
        releaseCodec();
        MediaFormat format = MediaFormat.createAudioFormat(mimeType, SAMPLE_RATE, CHANNEL_COUNT);
        switch (codecId) {
            case Codecs.AUDIO_OPUS:
                // csd-0: OpusHead; csd-1: codec delay (pre-skip) and csd-2: seek pre-roll, in ns.
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
                format.setByteBuffer("csd-1", nativeLong(opusPreSkipNs(config)));
                format.setByteBuffer("csd-2", nativeLong(OPUS_SEEK_PREROLL_NS));
                break;
            case Codecs.AUDIO_FLAC:
                format.setByteBuffer("csd-0", ByteBuffer.wrap(flacCsd(config)));
                break;
            case Codecs.AUDIO_AAC:
            default:
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
                break;
        }
        codec = MediaCodec.createDecoderByType(mimeType);
        codec.configure(format, null, null, 0);
        codec.start();
    }

    public synchronized void decode(byte[] encodedSample, int size, long presentationTimeUs) {
        if (codecId == Codecs.AUDIO_RAW) {
            if (audioTrack != null && size > 0) {
                audioTrack.write(encodedSample, 0, size);
            }
            return;
        }
        if (codec == null) {
            return;
        }
        try {
            int inputIndex = codec.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                if (inputBuffer != null && size <= inputBuffer.capacity()) {
                    inputBuffer.clear();
                    inputBuffer.put(encodedSample, 0, size);
                    codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0);
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0);
                }
            }
            drainOutput();
        } catch (IllegalStateException ignored) {
            // A codec can stop while the overlay or connection is being closed.
        }
    }

    private void drainOutput() {
        while (codec != null) {
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = codec.getOutputFormat();
                int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE;
                int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : CHANNEL_COUNT;
                configureAudioTrack(sampleRate, channelCount);
                continue;
            }
            if (outputIndex < 0) {
                return;
            }

            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                if (outputBuffer != null && bufferInfo.size > 0) {
                    if (audioTrack == null) {
                        // Some decoders never report a format change.
                        configureAudioTrack(SAMPLE_RATE, CHANNEL_COUNT);
                    }
                    if (audioTrack != null) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        if (pcmBuffer.length < bufferInfo.size) {
                            pcmBuffer = new byte[bufferInfo.size];
                        }
                        outputBuffer.get(pcmBuffer, 0, bufferInfo.size);
                        audioTrack.write(pcmBuffer, 0, bufferInfo.size);
                    }
                }
            } finally {
                codec.releaseOutputBuffer(outputIndex, false);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void configureAudioTrack(int sampleRate, int channelCount) {
        int channelConfig = channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBufferSize, sampleRate * channelCount * 2 / 5);

        releaseAudioTrack();
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
        if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            audioTrack.play();
        } else {
            releaseAudioTrack();
        }
    }

    public synchronized void release() {
        releaseCodec();
        releaseAudioTrack();
    }

    private void releaseCodec() {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (IllegalStateException ignored) {
            // The codec was not started or was already stopped.
        }
        codec.release();
        codec = null;
    }

    private void releaseAudioTrack() {
        if (audioTrack == null) {
            return;
        }
        try {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.stop();
        } catch (IllegalStateException ignored) {
            // The track may have failed during initialization.
        }
        audioTrack.release();
        audioTrack = null;
    }

    /** Pre-skip (little-endian u16 at offset 10 of OpusHead, in 48 kHz samples) as nanoseconds. */
    static long opusPreSkipNs(byte[] opusHead) {
        if (opusHead == null || opusHead.length < 12) {
            return 0;
        }
        int preSkip = (opusHead[10] & 0xff) | ((opusHead[11] & 0xff) << 8);
        return preSkip * 1_000_000_000L / SAMPLE_RATE;
    }

    /**
     * The server sends the bare 34-byte STREAMINFO block; Android's FLAC decoder expects the
     * "fLaC" marker followed by the metadata block header and the block.
     */
    static byte[] flacCsd(byte[] config) {
        if (config.length >= 4 && config[0] == 'f' && config[1] == 'L' && config[2] == 'a' && config[3] == 'C') {
            return config;
        }
        byte[] csd = new byte[8 + config.length];
        csd[0] = 'f';
        csd[1] = 'L';
        csd[2] = 'a';
        csd[3] = 'C';
        csd[4] = (byte) 0x80; // last metadata block, type 0 (STREAMINFO)
        csd[5] = (byte) (config.length >> 16);
        csd[6] = (byte) (config.length >> 8);
        csd[7] = (byte) config.length;
        System.arraycopy(config, 0, csd, 8, config.length);
        return csd;
    }

    private static ByteBuffer nativeLong(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        buffer.putLong(value);
        buffer.flip();
        return buffer;
    }
}
