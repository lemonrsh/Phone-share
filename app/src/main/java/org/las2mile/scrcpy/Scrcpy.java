package org.las2mile.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import org.las2mile.scrcpy.decoder.AudioDecoder;
import org.las2mile.scrcpy.decoder.VideoDecoder;
import org.las2mile.scrcpy.model.VideoPacket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Viewer-side transport and decoder for the bundled scrcpy v5.0.1 server. */
public class Scrcpy extends Service {
    private static final String TAG = "Scrcpy";
    private static final int VIDEO_CODEC_H264 = 0x68323634;
    private static final int AUDIO_CODEC_AAC = 0x00616163;
    private static final long PACKET_FLAG_SESSION = 1L << 63;
    private static final long PACKET_FLAG_CONFIG = 1L << 62;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 61;
    private static final long PACKET_FLAG_MASK = PACKET_FLAG_SESSION | PACKET_FLAG_CONFIG | PACKET_FLAG_KEY_FRAME;
    private static final int MAX_PACKET_SIZE = 32 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int CONNECT_RETRY_COUNT = 100;

    private final AtomicBoolean updateAvailable = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object controlLock = new Object();
    private final IBinder binder = new MyServiceBinder();

    private String serverAddress;
    private Surface surface;
    private int screenWidth;
    private int screenHeight;
    private volatile int streamWidth;
    private volatile int streamHeight;
    private volatile boolean socketConnected;

    private Socket videoSocket;
    private Socket audioSocket;
    private Socket controlSocket;
    private DataOutputStream controlOutput;
    private DataInputStream videoInput;

    private VideoDecoder videoDecoder;
    private volatile AudioDecoder audioDecoder;
    private Thread connectionThread;
    private Thread audioThread;
    private ScrcpySessionOptions sessionOptions = new ScrcpySessionOptions();
    private ServiceCallbacks serviceCallbacks;
    private final int[] remoteDeviceResolution = new int[2];

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ScrcpySessionOptions.hasExtras(intent)) {
            sessionOptions = ScrcpySessionOptions.fromIntent(intent);
        }
        return START_NOT_STICKY;
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    public void setParms(Surface newSurface, int newWidth, int newHeight) {
        screenWidth = newWidth;
        screenHeight = newHeight;
        surface = newSurface;
        if (videoDecoder != null) {
            videoDecoder.start();
        }
        updateAvailable.set(true);
    }

    public void start(Surface surface, String serverAddress, int screenHeight, int screenWidth) {
        if (running.get()) {
            setParms(surface, screenWidth, screenHeight);
            return;
        }
        this.surface = surface;
        this.serverAddress = serverAddress;
        this.screenHeight = screenHeight;
        this.screenWidth = screenWidth;
        this.videoDecoder = new VideoDecoder();
        this.videoDecoder.start();
        this.running.set(true);
        this.connectionThread = new Thread(this::startConnection, "scrcpy-viewer-connection");
        this.connectionThread.start();
    }

    public void pause() {
        if (videoDecoder != null) {
            videoDecoder.pause();
        }
    }

    public void resume() {
        if (videoDecoder != null) {
            videoDecoder.resume();
        }
    }

    public void StopService() {
        running.set(false);
        closeSockets();
        releaseAudioDecoder();
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running.set(false);
        closeSockets();
        releaseAudioDecoder();
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder = null;
        }
        super.onDestroy();
    }

    public boolean touchevent(MotionEvent touchEvent, int displayWidth, int displayHeight) {
        if (sessionOptions.noControl || controlOutput == null || streamWidth <= 0 || streamHeight <= 0
                || displayWidth <= 0 || displayHeight <= 0) {
            return true;
        }
        int pointerIndex = Math.min(touchEvent.getActionIndex(), touchEvent.getPointerCount() - 1);
        int action = touchEvent.getActionMasked();
        int x = clamp((int) (touchEvent.getX(pointerIndex) * streamWidth / displayWidth), 0, streamWidth - 1);
        int y = clamp((int) (touchEvent.getY(pointerIndex) * streamHeight / displayHeight), 0, streamHeight - 1);
        int pressure = clamp(Math.round(touchEvent.getPressure(pointerIndex) * 65535f), 0, 65535);
        int pointerId = touchEvent.getPointerId(pointerIndex);

        synchronized (controlLock) {
            if (controlOutput == null) {
                return true;
            }
            try {
                controlOutput.writeByte(2); // TYPE_INJECT_TOUCH_EVENT
                controlOutput.writeByte(action);
                controlOutput.writeLong(pointerId);
                controlOutput.writeInt(x);
                controlOutput.writeInt(y);
                controlOutput.writeShort(streamWidth);
                controlOutput.writeShort(streamHeight);
                controlOutput.writeShort(pressure);
                controlOutput.writeInt(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? touchEvent.getActionButton() : 0);
                controlOutput.writeInt(touchEvent.getButtonState());
                controlOutput.flush();
            } catch (IOException e) {
                Log.w(TAG, "Could not send touch event", e);
            }
        }
        return true;
    }

    public int[] get_remote_device_resolution() {
        return remoteDeviceResolution;
    }

    public boolean check_socket_connection() {
        return socketConnected;
    }

    public void sendKeyevent(int keycode) {
        sendKeyAction(MotionEvent.ACTION_DOWN, keycode);
        sendKeyAction(MotionEvent.ACTION_UP, keycode);
    }

    public void setRemoteDisplayPower(boolean on) {
        synchronized (controlLock) {
            if (controlOutput == null) {
                return;
            }
            try {
                controlOutput.writeByte(10); // TYPE_SET_DISPLAY_POWER
                controlOutput.writeBoolean(on);
                controlOutput.flush();
            } catch (IOException e) {
                Log.w(TAG, "Could not change remote display power", e);
            }
        }
    }

    private void sendKeyAction(int action, int keycode) {
        synchronized (controlLock) {
            if (controlOutput == null || sessionOptions.noControl) {
                return;
            }
            try {
                controlOutput.writeByte(0); // TYPE_INJECT_KEYCODE
                controlOutput.writeByte(action == MotionEvent.ACTION_UP ? 1 : 0);
                controlOutput.writeInt(keycode);
                controlOutput.writeInt(0); // repeat
                controlOutput.writeInt(0); // meta state
                controlOutput.flush();
            } catch (IOException e) {
                Log.w(TAG, "Could not send key event", e);
            }
        }
    }

    private void startConnection() {
        Exception lastError = null;
        int attempts = CONNECT_RETRY_COUNT;
        while (running.get() && attempts-- > 0) {
            try {
                connectSockets();
                readInitialVideoHeader();
                if (sessionOptions.audioEnabled && audioSocket != null) {
                    audioThread = new Thread(this::readAudioStream, "scrcpy-viewer-audio");
                    audioThread.start();
                }
                readVideoStream();
                return;
            } catch (IOException | RuntimeException e) {
                lastError = e;
                closeSockets();
                if (!running.get()) {
                    return;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        socketConnected = false;
        if (running.get() && lastError != null) {
            Log.e(TAG, "Unable to connect to the scrcpy server", lastError);
        }
    }

    private void connectSockets() throws IOException {
        videoSocket = connect(serverAddress);
        videoInput = new DataInputStream(videoSocket.getInputStream());

        if (sessionOptions.audioEnabled) {
            audioSocket = connect(serverAddress);
        }
        if (!sessionOptions.noControl) {
            controlSocket = connect(serverAddress);
            synchronized (controlLock) {
                controlOutput = new DataOutputStream(controlSocket.getOutputStream());
            }
        }
    }

    private Socket connect(String address) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(address, ScrcpySessionOptions.SERVER_PORT), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private void readInitialVideoHeader() throws IOException {
        int dummyByte = videoInput.readUnsignedByte();
        if (dummyByte != 0) {
            throw new IOException("Unexpected scrcpy server handshake byte: " + dummyByte);
        }
        byte[] deviceName = new byte[64];
        videoInput.readFully(deviceName);
        int videoCodec = videoInput.readInt();
        if (videoCodec != VIDEO_CODEC_H264) {
            throw new IOException("Server selected an unsupported video codec: 0x" + Integer.toHexString(videoCodec));
        }
    }

    private void readVideoStream() throws IOException {
        int metadataHigh = videoInput.readInt();
        while (running.get()) {
            if ((metadataHigh & 0x80000000) != 0) {
                int width = videoInput.readInt();
                int height = videoInput.readInt();
                handleVideoSession(width, height);
                metadataHigh = videoInput.readInt();
                continue;
            }

            int metadataLow = videoInput.readInt();
            int packetSize = videoInput.readInt();
            if (packetSize < 0 || packetSize > MAX_PACKET_SIZE) {
                throw new IOException("Invalid video packet size: " + packetSize);
            }
            byte[] packet = new byte[packetSize];
            videoInput.readFully(packet);

            long ptsAndFlags = ((long) metadataHigh << 32) | (metadataLow & 0xffffffffL);
            boolean config = (ptsAndFlags & PACKET_FLAG_CONFIG) != 0;
            boolean keyFrame = (ptsAndFlags & PACKET_FLAG_KEY_FRAME) != 0;
            long pts = ptsAndFlags & ~PACKET_FLAG_MASK;

            if (config) {
                VideoPacket.StreamSettings streamSettings = VideoPacket.getStreamSettings(packet);
                waitForSurfaceIfNeeded();
                if (videoDecoder != null && surface != null) {
                    videoDecoder.configure(surface, streamWidth, streamHeight, streamSettings.sps, streamSettings.pps);
                }
            } else if (packetSize > 0 && videoDecoder != null) {
                int codecFlags = keyFrame ? 1 : 0; // MediaCodec.BUFFER_FLAG_KEY_FRAME
                videoDecoder.decodeSample(packet, 0, packet.length, pts, codecFlags);
            }
            metadataHigh = videoInput.readInt();
        }
    }

    private void handleVideoSession(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IOException("Invalid video dimensions from server: " + width + "x" + height);
        }
        boolean firstSession = streamWidth == 0 || streamHeight == 0;
        streamWidth = width;
        streamHeight = height;
        remoteDeviceResolution[0] = width;
        remoteDeviceResolution[1] = height;
        socketConnected = true;

        if (firstSession) {
            updateAvailable.set(true);
            if (sessionOptions.turnScreenOffOnConnect && !sessionOptions.noControl) {
                setRemoteDisplayPower(false);
            }
            return;
        }

        ServiceCallbacks callbacks = serviceCallbacks;
        if (callbacks != null) {
            callbacks.loadNewRotation();
        }
    }

    private void waitForSurfaceIfNeeded() throws IOException {
        while (running.get() && !updateAvailable.get()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the viewer surface", e);
            }
        }
        updateAvailable.set(false);
    }

    private void readAudioStream() {
        AudioDecoder decoder = null;
        try {
            Socket socket = audioSocket;
            if (socket == null) {
                return;
            }
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int audioCodec = input.readInt();
            if (audioCodec == 0) {
                Log.i(TAG, "Remote device did not provide an audio stream");
                return;
            }
            if (audioCodec != AUDIO_CODEC_AAC) {
                throw new IOException("Server selected an unsupported audio codec: 0x" + Integer.toHexString(audioCodec));
            }

            decoder = new AudioDecoder();
            audioDecoder = decoder;
            while (running.get()) {
                long ptsAndFlags = input.readLong();
                int packetSize = input.readInt();
                if (packetSize < 0 || packetSize > MAX_PACKET_SIZE) {
                    throw new IOException("Invalid audio packet size: " + packetSize);
                }
                byte[] packet = new byte[packetSize];
                input.readFully(packet);
                if (!running.get()) {
                    break;
                }
                if ((ptsAndFlags & PACKET_FLAG_CONFIG) != 0) {
                    decoder.configure(packet);
                } else if (packetSize > 0) {
                    decoder.decode(packet, ptsAndFlags & ~PACKET_FLAG_MASK);
                }
            }
        } catch (EOFException e) {
            Log.i(TAG, "Audio stream closed");
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

    private void releaseAudioDecoder() {
        AudioDecoder decoder = audioDecoder;
        audioDecoder = null;
        if (decoder != null) {
            decoder.release();
        }
    }

    private void closeSockets() {
        synchronized (controlLock) {
            controlOutput = null;
        }
        socketConnected = false;
        closeQuietly(videoSocket);
        closeQuietly(audioSocket);
        closeQuietly(controlSocket);
        videoSocket = null;
        audioSocket = null;
        controlSocket = null;
        videoInput = null;
        streamWidth = 0;
        streamHeight = 0;
        remoteDeviceResolution[0] = 0;
        remoteDeviceResolution[1] = 0;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a socket during disconnect is best effort.
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public interface ServiceCallbacks {
        void loadNewRotation();
    }

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }
}
