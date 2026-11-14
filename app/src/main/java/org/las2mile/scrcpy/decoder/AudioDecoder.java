package org.las2mile.scrcpy.decoder;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Small AAC-to-AudioTrack playback path for the scrcpy server audio stream. */
public final class AudioDecoder {
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int DEFAULT_SAMPLE_RATE = 48_000;
    private static final int DEFAULT_CHANNEL_COUNT = 2;

    private MediaCodec codec;
    private AudioTrack audioTrack;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    public synchronized void configure(byte[] codecSpecificData) throws IOException {
        releaseCodec();
        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(codecSpecificData));
        codec = MediaCodec.createDecoderByType(MIME_TYPE);
        codec.configure(format, null, null, 0);
        codec.start();
    }

    public synchronized void decode(byte[] encodedSample, long presentationTimeUs) {
        if (codec == null) {
            return;
        }
        try {
            int inputIndex = codec.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                if (inputBuffer != null && encodedSample.length <= inputBuffer.capacity()) {
                    inputBuffer.clear();
                    inputBuffer.put(encodedSample);
                    codec.queueInputBuffer(inputIndex, 0, encodedSample.length, presentationTimeUs, 0);
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
                configureAudioTrack(codec.getOutputFormat());
                continue;
            }
            if (outputIndex < 0) {
                return;
            }

            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                if (outputBuffer != null && bufferInfo.size > 0 && audioTrack != null) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    byte[] pcm = new byte[bufferInfo.size];
                    outputBuffer.get(pcm);
                    audioTrack.write(pcm, 0, pcm.length);
                }
            } finally {
                codec.releaseOutputBuffer(outputIndex, false);
            }
        }
    }

    private void configureAudioTrack(MediaFormat format) {
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_SAMPLE_RATE;
        int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : DEFAULT_CHANNEL_COUNT;
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
}
