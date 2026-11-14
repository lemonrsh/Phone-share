package org.las2mile.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** H.264 decoder feeding the local SurfaceView. */
public final class VideoDecoder {
    private final Object codecLock = new Object();
    private final AtomicBoolean configured = new AtomicBoolean(false);
    private volatile MediaCodec codec;
    private volatile Worker worker;

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        Worker currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
        Worker currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.configure(surface, width, height, csd0, csd1);
        }
    }

    public synchronized void start() {
        if (worker == null || !worker.isAlive()) {
            worker = new Worker();
            worker.start();
        } else {
            worker.resumeDecoding();
        }
    }

    public void pause() {
        Worker currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.pauseDecoding();
        }
    }

    public void resume() {
        start();
    }

    public synchronized void stop() {
        Worker currentWorker = worker;
        worker = null;
        configured.set(false);
        if (currentWorker != null) {
            currentWorker.stopDecoding();
        }
    }

    private void releaseCodec() {
        synchronized (codecLock) {
            configured.set(false);
            MediaCodec currentCodec = codec;
            codec = null;
            if (currentCodec != null) {
                try {
                    currentCodec.stop();
                } catch (IllegalStateException ignored) {
                    // The decoder was not started or was already stopped.
                }
                currentCodec.release();
            }
        }
    }

    private final class Worker extends Thread {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean paused = new AtomicBoolean(false);

        Worker() {
            super("scrcpy-video-decoder");
        }

        void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
            synchronized (codecLock) {
                releaseCodec();
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
                format.setByteBuffer("csd-0", csd0);
                format.setByteBuffer("csd-1", csd1);
                try {
                    MediaCodec newCodec = MediaCodec.createDecoderByType("video/avc");
                    newCodec.configure(format, surface, null, 0);
                    newCodec.start();
                    codec = newCodec;
                    configured.set(true);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create H.264 decoder", e);
                }
            }
        }

        void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if (!running.get() || paused.get() || !configured.get()) {
                return;
            }
            synchronized (codecLock) {
                MediaCodec currentCodec = codec;
                if (currentCodec == null || !configured.get()) {
                    return;
                }
                try {
                    int index = currentCodec.dequeueInputBuffer(10_000);
                    if (index < 0) {
                        return;
                    }
                    ByteBuffer buffer = currentCodec.getInputBuffer(index);
                    if (buffer == null || size > buffer.capacity()) {
                        currentCodec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0);
                        return;
                    }
                    buffer.clear();
                    buffer.put(data, offset, size);
                    currentCodec.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
                } catch (IllegalStateException ignored) {
                    // The stream or surface is transitioning.
                }
            }
        }

        void pauseDecoding() {
            paused.set(true);
        }

        void resumeDecoding() {
            paused.set(false);
        }

        void stopDecoding() {
            running.set(false);
            interrupt();
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (running.get()) {
                    if (paused.get() || !configured.get()) {
                        Thread.sleep(10);
                        continue;
                    }
                    synchronized (codecLock) {
                        MediaCodec currentCodec = codec;
                        if (currentCodec == null || !configured.get()) {
                            continue;
                        }
                        try {
                            int index = currentCodec.dequeueOutputBuffer(info, 0);
                            if (index >= 0) {
                                currentCodec.releaseOutputBuffer(index, true);
                                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    break;
                                }
                            }
                        } catch (IllegalStateException ignored) {
                            // A surface transition may briefly invalidate the codec.
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                releaseCodec();
            }
        }
    }
}
