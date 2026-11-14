package org.las2mile.scrcpy;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import org.las2mile.scrcpy.decoder.AudioDecoder;
import org.las2mile.scrcpy.decoder.Codecs;
import org.las2mile.scrcpy.decoder.VideoDecoder;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Viewer side of one scrcpy v5.0.1 session: connects the video/audio/control
 * sockets, decodes the streams and sends input.
 *
 * <p>Threading: the public methods may be called from the main thread; they
 * never perform network I/O there. Network reads run on dedicated threads,
 * control messages are written by a single writer thread, and
 * {@link Listener} callbacks are delivered on the main thread (never after
 * {@link #stop()}).
 */
public final class ScrcpyClient {
    private static final String TAG = "ScrcpyClient";

    private static final long PACKET_FLAG_CONFIG = 1L << 62;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 61;
    private static final long PACKET_FLAG_MASK = (1L << 63) | PACKET_FLAG_CONFIG | PACKET_FLAG_KEY_FRAME;
    private static final int MAX_PACKET_SIZE = 32 * 1024 * 1024;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 3_000;
    private static final long SERVER_START_TIMEOUT_MS = 20_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int MAX_PENDING_CONTROL_MESSAGES = 512;
    private static final long KEY_FRAME_REQUEST_INTERVAL_MS = 1_000;

    public interface Listener {
        /** The first video frame size is known; mirroring has started. */
        void onConnected(int videoWidth, int videoHeight);

        /** The remote device rotated or the stream was resized. */
        void onVideoSizeChanged(int videoWidth, int videoHeight);

        /** The session ended unexpectedly; the client is already stopped. */
        void onDisconnected(String message);

        /** The target's clipboard changed (only with clipboard sync enabled). */
        void onDeviceClipboard(String text);
    }

    private final String host;
    private final ScrcpySessionOptions options;
    private final AdbLauncher.Session adbSession;
    /** Swapped when the session moves between full screen and the floating window. */
    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final LinkedBlockingQueue<byte[]> controlQueue = new LinkedBlockingQueue<>();
    private final VideoDecoder videoDecoder;

    private volatile Socket videoSocket;
    private volatile Socket audioSocket;
    private volatile Socket controlSocket;
    private volatile AudioDecoder audioDecoder;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile boolean connected;
    private volatile long lastKeyFrameRequest;
    private volatile Thread videoThread;
    private volatile Thread audioThread;
    private volatile Thread controlThread;
    private volatile Thread deviceMessageThread;
    private volatile String deviceName = "";
    private final int expectedVideoCodec;

    public ScrcpyClient(String host, ScrcpySessionOptions options, AdbLauncher.Session adbSession, Listener listener) {
        this.host = host;
        this.options = options.copy();
        this.adbSession = adbSession;
        this.listener = listener;
        this.expectedVideoCodec = Codecs.videoId(this.options.videoCodec);
        this.videoDecoder = new VideoDecoder(Codecs.videoMime(this.options.videoCodec), this::requestKeyFrame);
    }

    public void start() {
        if (stopped.get() || !running.compareAndSet(false, true)) {
            return;
        }
        DiagnosticLog.i("client", "Connecting to the server at " + host + ":" + ScrcpySessionOptions.SERVER_PORT
                + " (video=" + options.videoCodec + ", audio=" + (options.audioEnabled ? options.audioCodec : "off")
                + ", control=" + !options.noControl + ")");
        videoThread = new Thread(this::runVideo, "scrcpy-video");
        videoThread.setPriority(Thread.MAX_PRIORITY);
        videoThread.start();
    }

    /** Stops the session and releases every resource. Idempotent; does not block on the network. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        mainHandler.removeCallbacksAndMessages(null);
        controlQueue.clear();
        controlQueue.offer(new byte[0]); // wakes the writer thread so that it can exit
        interrupt(controlThread);
        videoDecoder.release();
        AudioDecoder decoder = audioDecoder;
        audioDecoder = null;
        if (decoder != null) {
            decoder.release();
        }
        final Socket video = videoSocket;
        final Socket audio = audioSocket;
        final Socket control = controlSocket;
        videoSocket = null;
        audioSocket = null;
        controlSocket = null;
        Thread closer = new Thread(() -> {
            closeQuietly(video);
            closeQuietly(audio);
            closeQuietly(control);
        }, "scrcpy-close");
        closer.setDaemon(true);
        closer.start();
        if (adbSession != null) {
            adbSession.closeAsync();
        }
    }

    public boolean isConnected() {
        return connected && running.get();
    }

    /**
     * Hands the session to another host (full screen to floating window). Must be called on the
     * main thread; callbacks already posted are delivered to the new listener.
     */
    public void setListener(Listener newListener) {
        listener = newListener;
    }

    public ScrcpySessionOptions getOptions() {
        return options.copy();
    }

    /** The target's device name from the handshake (empty before it is known). */
    public String getDeviceName() {
        return deviceName;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    /** Sets the Surface to render into, or {@code null} when it is destroyed. */
    public void setSurface(Surface surface) {
        videoDecoder.setSurface(surface);
    }

    /**
     * Converts a local touch event on a view of the given size into scrcpy
     * touch messages. The MotionEvent is read synchronously (it is recycled by
     * the framework after dispatch); only the encoded bytes are sent later.
     */
    public boolean sendTouch(MotionEvent event, int viewWidth, int viewHeight) {
        int width = videoWidth;
        int height = videoHeight;
        if (options.noControl || options.cameraSource || !isConnected() || width <= 0 || height <= 0
                || viewWidth <= 0 || viewHeight <= 0) {
            return true;
        }
        int actionMasked = event.getActionMasked();
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                queueTouch(event, event.getActionIndex(), ControlMessages.ACTION_DOWN, width, height, viewWidth, viewHeight);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                queueTouch(event, event.getActionIndex(), ControlMessages.ACTION_UP, width, height, viewWidth, viewHeight);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    queueTouch(event, i, ControlMessages.ACTION_MOVE, width, height, viewWidth, viewHeight);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    queueTouch(event, i, ControlMessages.ACTION_UP, width, height, viewWidth, viewHeight);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void queueTouch(MotionEvent event, int pointerIndex, int action, int width, int height,
                            int viewWidth, int viewHeight) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            return;
        }
        int x = clamp((int) (event.getX(pointerIndex) * width / viewWidth), 0, width - 1);
        int y = clamp((int) (event.getY(pointerIndex) * height / viewHeight), 0, height - 1);
        float pressure = action == ControlMessages.ACTION_UP ? 0f : event.getPressure(pointerIndex);
        queueControl(ControlMessages.touch(action, event.getPointerId(pointerIndex), x, y, width, height,
                pressure, 0, 0));
    }

    public void sendKeyEvent(int keycode) {
        if (options.noControl) {
            return;
        }
        queueControl(ControlMessages.keycode(ControlMessages.KEY_ACTION_DOWN, keycode, 0, 0));
        queueControl(ControlMessages.keycode(ControlMessages.KEY_ACTION_UP, keycode, 0, 0));
    }

    public void setDisplayPower(boolean on) {
        if (options.noControl) {
            return;
        }
        queueControl(ControlMessages.setDisplayPower(on));
    }

    /** Sends this phone's clipboard text to the target (clipboard sync). */
    public void setClipboard(String text) {
        if (options.noControl || text == null || !isConnected()) {
            return;
        }
        queueControl(ControlMessages.setClipboard(0, text, false));
    }

    private void requestKeyFrame() {
        if (options.noControl || !connected) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastKeyFrameRequest < KEY_FRAME_REQUEST_INTERVAL_MS) {
            return;
        }
        lastKeyFrameRequest = now;
        queueControl(ControlMessages.resetVideo());
    }

    private void queueControl(byte[] message) {
        if (!running.get() || options.noControl) {
            return;
        }
        if (controlQueue.size() >= MAX_PENDING_CONTROL_MESSAGES) {
            // The link is stalled; dropping stale input is better than unbounded memory use.
            controlQueue.clear();
        }
        controlQueue.offer(message);
    }

    private void runVideo() {
        String failure = null;
        try {
            connectSockets();
            DataInputStream input = readVideoHeader();
            if (audioSocket != null) {
                audioThread = new Thread(this::runAudio, "scrcpy-audio");
                audioThread.start();
            }
            if (controlSocket != null) {
                controlThread = new Thread(this::runControl, "scrcpy-control");
                controlThread.start();
                if (options.clipboardAutosync) {
                    deviceMessageThread = new Thread(this::runDeviceMessages, "scrcpy-device-messages");
                    deviceMessageThread.setDaemon(true);
                    deviceMessageThread.start();
                }
            }
            readVideoStream(input);
            failure = "The remote device closed the stream.";
        } catch (EOFException e) {
            failure = connected ? "The remote device closed the stream." : describeStartupFailure(e);
        } catch (IOException | RuntimeException e) {
            if (running.get()) {
                Log.w(TAG, "Video stream stopped", e);
                DiagnosticLog.e("client", "Video stream stopped", e);
            }
            failure = connected ? "Connection lost: " + describe(e) : describeStartupFailure(e);
        } finally {
            if (running.get()) {
                DiagnosticLog.i("client", "Session ended: " + failure);
                notifyDisconnected(failure);
            } else {
                // stop() may have run while sockets were still being opened.
                closeQuietly(videoSocket);
                closeQuietly(audioSocket);
                closeQuietly(controlSocket);
            }
        }
    }

    private void connectSockets() throws IOException {
        long deadline = SystemClock.uptimeMillis() + SERVER_START_TIMEOUT_MS;
        Socket video = null;
        while (video == null) {
            ensureRunning();
            try {
                video = openSocket(1024 * 1024);
            } catch (IOException e) {
                if (adbSession != null && !adbSession.isServerAlive()) {
                    throw new IOException(serverStoppedMessage(), e);
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    throw new IOException("The scrcpy server did not open port " + ScrcpySessionOptions.SERVER_PORT
                            + " on " + host + ".", e);
                }
                sleep(100);
            }
        }
        videoSocket = video;
        DiagnosticLog.i("client", "Video socket connected");
        if (options.audioEnabled) {
            audioSocket = openSocket(256 * 1024);
        }
        if (!options.noControl) {
            controlSocket = openSocket(0);
        }
        ensureRunning();
    }

    private Socket openSocket(int receiveBufferSize) throws IOException {
        Socket socket = new Socket();
        try {
            if (receiveBufferSize > 0) {
                // Must be set before connecting so that TCP window scaling can use it.
                socket.setReceiveBufferSize(receiveBufferSize);
            }
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            if (CloudTunnel.isTunnelHost(host)) {
                // Through the shared phone's Cloudflare tunnel (see ShareService).
                CloudTunnel.connect(socket, host, CloudTunnel.TARGET_SCRCPY, SOCKET_CONNECT_TIMEOUT_MS);
            } else {
                socket.connect(new InetSocketAddress(host, ScrcpySessionOptions.SERVER_PORT), SOCKET_CONNECT_TIMEOUT_MS);
            }
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private DataInputStream readVideoHeader() throws IOException {
        Socket socket = videoSocket;
        if (socket == null) {
            throw new IOException("Stopped");
        }
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        int dummyByte = input.readUnsignedByte();
        if (dummyByte != 0) {
            throw new IOException("Unexpected scrcpy handshake byte: " + dummyByte);
        }
        byte[] deviceName = new byte[64];
        input.readFully(deviceName);
        int codec = input.readInt();
        this.deviceName = decodeDeviceName(deviceName);
        DiagnosticLog.i("client", "Handshake OK: device '" + this.deviceName + "', codec "
                + Codecs.describeId(codec));
        if (codec == 0 || codec == 1) {
            throw new IOException("The remote device could not start "
                    + (options.cameraSource ? "the camera." : "screen capture.")
                    + (options.videoCodec.equals("h264") ? "" : " Its encoder may not support "
                    + Codecs.describeId(expectedVideoCodec) + "; try H.264."));
        }
        if (codec != expectedVideoCodec) {
            throw new IOException("Unexpected video codec " + Codecs.describeId(codec) + " (asked for "
                    + Codecs.describeId(expectedVideoCodec) + ")");
        }
        return input;
    }

    private void readVideoStream(DataInputStream input) throws IOException {
        byte[] packet = new byte[256 * 1024];
        boolean handshakeTimeoutCleared = false;
        while (running.get()) {
            int header = input.readInt();
            if ((header & 0x80000000) != 0) {
                int width = input.readInt();
                int height = input.readInt();
                handleSession(width, height);
                if (!handshakeTimeoutCleared) {
                    // A static screen produces no frames: never time out once streaming.
                    Socket socket = videoSocket;
                    if (socket != null) {
                        socket.setSoTimeout(0);
                    }
                    handshakeTimeoutCleared = true;
                }
                continue;
            }

            int low = input.readInt();
            int size = input.readInt();
            if (size < 0 || size > MAX_PACKET_SIZE) {
                throw new IOException("Invalid video packet size: " + size);
            }
            if (size > packet.length) {
                packet = new byte[Math.max(size, packet.length * 2)];
            }
            input.readFully(packet, 0, size);

            long ptsAndFlags = ((long) header << 32) | (low & 0xffffffffL);
            if ((ptsAndFlags & PACKET_FLAG_CONFIG) != 0) {
                byte[] config = new byte[size];
                System.arraycopy(packet, 0, config, 0, size);
                videoDecoder.configure(videoWidth, videoHeight, config);
            } else if (size > 0) {
                boolean keyFrame = (ptsAndFlags & PACKET_FLAG_KEY_FRAME) != 0;
                videoDecoder.decode(packet, size, ptsAndFlags & ~PACKET_FLAG_MASK, keyFrame);
            }
        }
    }

    private void handleSession(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IOException("Invalid video size from server: " + width + "x" + height);
        }
        boolean changed = width != videoWidth || height != videoHeight;
        videoWidth = width;
        videoHeight = height;
        if (!connected) {
            connected = true;
            if (options.turnScreenOffOnConnect) {
                setDisplayPower(false);
            }
            String app = options.startApp == null ? "" : options.startApp.trim();
            if (!app.isEmpty() && !options.noControl) {
                DiagnosticLog.i("client", "Starting app on the target: " + app);
                queueControl(ControlMessages.startApp(app));
            }
            if (options.timeLimitSeconds > 0) {
                final int limit = options.timeLimitSeconds;
                mainHandler.postDelayed(() -> {
                    if (running.get()) {
                        DiagnosticLog.i("client", "Time limit reached (" + limit + " s)");
                        stop();
                        listener.onDisconnected("Time limit reached (" + limit + " s).");
                    }
                }, limit * 1000L);
            }
            postToListener(() -> listener.onConnected(width, height));
        } else if (changed) {
            postToListener(() -> listener.onVideoSizeChanged(width, height));
        }
    }

    private void runAudio() {
        AudioDecoder decoder = null;
        try {
            Socket socket = audioSocket;
            if (socket == null) {
                return;
            }
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 32 * 1024));
            int codec = input.readInt();
            if (codec == 0) {
                Log.i(TAG, "The remote device does not provide audio (Android 11+ is required for capture)");
                return;
            }
            if (codec == 1) {
                Log.w(TAG, "The remote device failed to start audio capture");
                return;
            }
            if (codec != Codecs.AUDIO_AAC && codec != Codecs.AUDIO_OPUS && codec != Codecs.AUDIO_FLAC
                    && codec != Codecs.AUDIO_RAW) {
                throw new IOException("Unsupported audio codec " + Codecs.describeId(codec));
            }
            DiagnosticLog.i("client", "Audio stream: " + Codecs.describeId(codec));
            decoder = new AudioDecoder(codec);
            audioDecoder = decoder;
            byte[] packet = new byte[16 * 1024];
            while (running.get()) {
                long ptsAndFlags = input.readLong();
                int size = input.readInt();
                if (size < 0 || size > MAX_PACKET_SIZE) {
                    throw new IOException("Invalid audio packet size: " + size);
                }
                if ((ptsAndFlags & PACKET_FLAG_CONFIG) != 0) {
                    byte[] config = new byte[size];
                    input.readFully(config);
                    decoder.configure(config);
                    continue;
                }
                if (size > packet.length) {
                    packet = new byte[Math.max(size, packet.length * 2)];
                }
                input.readFully(packet, 0, size);
                if (size > 0 && running.get()) {
                    decoder.decode(packet, size, ptsAndFlags & ~PACKET_FLAG_MASK);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (running.get()) {
                Log.w(TAG, "Audio playback stopped", e);
            }
        } finally {
            if (decoder != null) {
                decoder.release();
                if (audioDecoder == decoder) {
                    audioDecoder = null;
                }
            }
        }
    }

    private void runControl() {
        try {
            Socket socket = controlSocket;
            if (socket == null) {
                return;
            }
            OutputStream output = socket.getOutputStream();
            byte[] batch = new byte[4096];
            while (running.get()) {
                byte[] message = controlQueue.take();
                // Coalesce whatever is already queued into a single write.
                int length = 0;
                while (message != null) {
                    if (length + message.length > batch.length) {
                        if (length > 0) {
                            output.write(batch, 0, length);
                            length = 0;
                        }
                        if (message.length > batch.length) {
                            output.write(message);
                            message = controlQueue.poll();
                            continue;
                        }
                    }
                    System.arraycopy(message, 0, batch, length, message.length);
                    length += message.length;
                    message = controlQueue.poll();
                }
                if (length > 0) {
                    output.write(batch, 0, length);
                }
                output.flush();
            }
        } catch (InterruptedException e) {
            // Stopped.
        } catch (IOException | RuntimeException e) {
            if (running.get()) {
                Log.w(TAG, "Control channel closed", e);
            }
        }
    }

    /** Reads device messages (clipboard changes) sent by the server on the control socket. */
    private void runDeviceMessages() {
        try {
            Socket socket = controlSocket;
            if (socket == null) {
                return;
            }
            InputStream raw = socket.getInputStream();
            DataInputStream input = new DataInputStream(new BufferedInputStream(raw, 16 * 1024));
            while (running.get()) {
                int type = input.readUnsignedByte();
                switch (type) {
                    case ControlMessages.DEVICE_MSG_CLIPBOARD: {
                        int length = input.readInt();
                        if (length < 0 || length > ControlMessages.CLIPBOARD_TEXT_MAX_LENGTH) {
                            throw new IOException("Invalid clipboard length: " + length);
                        }
                        byte[] data = new byte[length];
                        input.readFully(data);
                        final String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        postToListener(() -> listener.onDeviceClipboard(text));
                        break;
                    }
                    case ControlMessages.DEVICE_MSG_ACK_CLIPBOARD:
                        input.readLong();
                        break;
                    case ControlMessages.DEVICE_MSG_UHID_OUTPUT: {
                        input.readUnsignedShort(); // id
                        int size = input.readUnsignedShort();
                        input.readFully(new byte[size]);
                        break;
                    }
                    default:
                        throw new IOException("Unknown device message type " + type);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (running.get()) {
                Log.w(TAG, "Device message channel closed", e);
            }
        }
    }

    static String decodeDeviceName(byte[] raw) {
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            ++end;
        }
        return new String(raw, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private void notifyDisconnected(String message) {
        final String text = message == null ? "Disconnected." : message;
        postToListener(() -> {
            stop();
            listener.onDisconnected(text);
        });
    }

    /** Posts a listener callback that is skipped if the client was stopped meanwhile. */
    private void postToListener(Runnable callback) {
        mainHandler.post(() -> {
            if (running.get()) {
                callback.run();
            }
        });
    }

    private String describeStartupFailure(Exception e) {
        if (adbSession != null && !adbSession.isServerAlive()) {
            return serverStoppedMessage();
        }
        if (e instanceof SocketTimeoutException) {
            return "The scrcpy server did not answer. If the target uses a VPN or several networks, "
                    + "check that this device can reach it directly.";
        }
        if (e instanceof EOFException) {
            return "The scrcpy server closed the connection during startup.";
        }
        String message = describe(e);
        return message.startsWith("The ") ? message : "Could not start mirroring: " + message;
    }

    private String serverStoppedMessage() {
        String reason = adbSession == null ? null : adbSession.describeServerFailure();
        return reason == null ? "The scrcpy server stopped on the remote device."
                : "The scrcpy server stopped: " + reason;
    }

    private void ensureRunning() throws IOException {
        if (!running.get()) {
            throw new IOException("Stopped");
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
