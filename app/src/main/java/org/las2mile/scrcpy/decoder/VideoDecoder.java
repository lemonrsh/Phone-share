package org.las2mile.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Low-latency H.264 decoder rendering into a Surface.
 *
 * <p>Input is queued from the network thread, output is drained and rendered
 * by a dedicated thread. Both only take the shared (read) side of a lock, so
 * they never block each other; codec creation, surface changes and release take
 * the exclusive side. The last codec configuration is remembered so that the
 * decoder can be rebuilt when the Surface is recreated (rotation, app switch)
 * or after a codec error, without waiting for a new stream from the server.
 */
public final class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long INPUT_TIMEOUT_US = 20_000;
    private static final long OUTPUT_TIMEOUT_US = 10_000;
    private static final int INPUT_ATTEMPTS = 25; // up to ~500 ms of decoder back-pressure

    /** Called when the decoder needs a key frame to resume (after a reset or drop). */
    public interface KeyFrameRequester {
        void requestKeyFrame();
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** Number of threads waiting for the exclusive lock; makes the input loop give way quickly. */
    private final AtomicInteger exclusiveWaiters = new AtomicInteger();
    private final KeyFrameRequester keyFrameRequester;
    private final Thread outputThread;

    // Guarded by lock (write side for mutation).
    private MediaCodec codec;
    private Surface surface;
    private ByteBuffer sps;
    private ByteBuffer pps;
    private int width;
    private int height;
    private boolean lowLatencySupported = true;

    private volatile boolean released;
    private volatile boolean waitingForKeyFrame = true;
    private volatile boolean codecFailed;

    public VideoDecoder(KeyFrameRequester keyFrameRequester) {
        this.keyFrameRequester = keyFrameRequester;
        outputThread = new Thread(this::drainOutput, "scrcpy-video-output");
        outputThread.setPriority(Thread.MAX_PRIORITY);
        outputThread.start();
    }

    /** Sets (or clears, with {@code null}) the output Surface. Blocks until the old Surface is unused. */
    public void setSurface(Surface newSurface) {
        boolean needKeyFrame = false;
        lockExclusive();
        try {
            if (released) {
                return;
            }
            if (newSurface != null && !newSurface.isValid()) {
                newSurface = null;
            }
            if (newSurface == surface && codec != null) {
                return;
            }
            surface = newSurface;
            releaseCodecLocked();
            if (surface != null && sps != null) {
                needKeyFrame = configureLocked();
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (needKeyFrame) {
            requestKeyFrame();
        }
    }

    /** Applies a new stream configuration (SPS/PPS) received from the server. */
    public void configure(int width, int height, ByteBuffer sps, ByteBuffer pps) {
        lockExclusive();
        try {
            if (released) {
                return;
            }
            this.width = width;
            this.height = height;
            this.sps = sps;
            this.pps = pps;
            releaseCodecLocked();
            if (surface != null) {
                // The server sends a key frame right after the configuration packet.
                configureLocked();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Queues one encoded frame. Called from the network thread. */
    public void decode(byte[] data, int size, long presentationTimeUs, boolean keyFrame) {
        if (released) {
            return;
        }
        if (codecFailed) {
            recover();
        }
        if (waitingForKeyFrame && !keyFrame) {
            return;
        }
        boolean dropped = false;
        boolean failed = false;
        lock.readLock().lock();
        try {
            MediaCodec current = codec;
            if (current == null) {
                return;
            }
            int index = -1;
            for (int attempt = 0; attempt < INPUT_ATTEMPTS && index < 0 && !released
                    && exclusiveWaiters.get() == 0; ++attempt) {
                index = current.dequeueInputBuffer(INPUT_TIMEOUT_US);
            }
            if (index < 0) {
                dropped = true;
                return;
            }
            ByteBuffer buffer = current.getInputBuffer(index);
            if (buffer == null || size > buffer.capacity()) {
                current.queueInputBuffer(index, 0, 0, presentationTimeUs, 0);
                dropped = true;
                return;
            }
            buffer.clear();
            buffer.put(data, 0, size);
            current.queueInputBuffer(index, 0, size, presentationTimeUs,
                    keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            if (keyFrame) {
                waitingForKeyFrame = false;
            }
        } catch (IllegalStateException e) {
            // Includes MediaCodec.CodecException.
            Log.w(TAG, "Decoder input failed", e);
            failed = true;
        } finally {
            lock.readLock().unlock();
            if (dropped && !keyFrame) {
                // Later frames reference the dropped one: skip until the next key frame.
                waitingForKeyFrame = true;
                requestKeyFrame();
            }
            if (failed) {
                codecFailed = true;
            }
        }
    }

    public void release() {
        released = true; // set first so that the input loop stops waiting for buffers
        lockExclusive();
        try {
            releaseCodecLocked();
            surface = null;
        } finally {
            lock.writeLock().unlock();
        }
        outputThread.interrupt();
    }

    private void recover() {
        boolean needKeyFrame = false;
        lockExclusive();
        try {
            if (!codecFailed || released) {
                return;
            }
            codecFailed = false;
            releaseCodecLocked();
            if (surface != null && surface.isValid() && sps != null) {
                needKeyFrame = configureLocked();
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (needKeyFrame) {
            requestKeyFrame();
        }
    }

    private void lockExclusive() {
        exclusiveWaiters.incrementAndGet();
        try {
            lock.writeLock().lock();
        } finally {
            exclusiveWaiters.decrementAndGet();
        }
    }

    private void requestKeyFrame() {
        if (keyFrameRequester != null && !released) {
            keyFrameRequester.requestKeyFrame();
        }
    }

    /** Must hold the write lock. Returns true when a codec was created. */
    private boolean configureLocked() {
        waitingForKeyFrame = true;
        if (surface == null || !surface.isValid() || sps == null || pps == null || width <= 0 || height <= 0) {
            return false;
        }
        MediaCodec newCodec = null;
        try {
            newCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            try {
                newCodec.configure(createFormat(lowLatencySupported), surface, null, 0);
            } catch (RuntimeException e) {
                if (!lowLatencySupported) {
                    throw e;
                }
                // Some vendor decoders reject the low-latency hints: retry without them.
                Log.w(TAG, "Decoder rejected low-latency configuration; retrying", e);
                lowLatencySupported = false;
                newCodec.release();
                newCodec = MediaCodec.createDecoderByType(MIME_TYPE);
                newCodec.configure(createFormat(false), surface, null, 0);
            }
            newCodec.start();
            codec = newCodec;
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not create the H.264 decoder", e);
            if (newCodec != null) {
                try {
                    newCodec.release();
                } catch (RuntimeException ignored) {
                    // Already failed.
                }
            }
            codec = null;
            return false;
        }
    }

    private MediaFormat createFormat(boolean lowLatency) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setByteBuffer("csd-0", sps.duplicate());
        format.setByteBuffer("csd-1", pps.duplicate());
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 1024 * 1024));
        if (lowLatency) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0); // real time
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
        }
        return format;
    }

    /** Must hold the write lock. */
    private void releaseCodecLocked() {
        MediaCodec current = codec;
        codec = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (RuntimeException ignored) {
            // The codec may already be in an error state.
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }

    private void drainOutput() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!released) {
            boolean idle = false;
            boolean failed = false;
            lock.readLock().lock();
            try {
                MediaCodec current = codec;
                if (current == null || codecFailed) {
                    idle = true;
                } else {
                    int index = current.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US);
                    if (index >= 0) {
                        // Render immediately: lowest latency for a live mirror.
                        current.releaseOutputBuffer(index, info.size > 0);
                    }
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "Decoder output failed", e);
                failed = true;
            } finally {
                lock.readLock().unlock();
            }
            if (failed) {
                codecFailed = true;
                idle = true;
            }
            if (idle) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    if (released) {
                        return;
                    }
                }
            }
        }
    }
}
