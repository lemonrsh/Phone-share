package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The upstream scrcpy connection normally uses adb forward/reverse and Android
 * local sockets.  The Android-to-Android app has no desktop adb client, so this
 * fork additionally accepts the same logical channels over a short-lived TCP
 * listener on the target device.
 */
public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;
    private static final String SOCKET_NAME_PREFIX = "scrcpy";
    private static final int TCP_ACCEPT_TIMEOUT_MS = 60_000;

    private static final class Channel implements Closeable {
        private final Closeable socket;
        private final InputStream input;
        private final OutputStream output;

        Channel(LocalSocket socket) throws IOException {
            this.socket = socket;
            input = socket.getInputStream();
            output = socket.getOutputStream();
        }

        Channel(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            input = socket.getInputStream();
            output = socket.getOutputStream();
        }

        InputStream getInputStream() {
            return input;
        }

        OutputStream getOutputStream() {
            return output;
        }

        void shutdown() throws IOException {
            if (socket instanceof LocalSocket) {
                LocalSocket localSocket = (LocalSocket) socket;
                localSocket.shutdownInput();
                localSocket.shutdownOutput();
            } else if (socket instanceof Socket) {
                Socket tcpSocket = (Socket) socket;
                tcpSocket.shutdownInput();
                tcpSocket.shutdownOutput();
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private final Channel videoChannel;
    private final Channel audioChannel;
    private final Channel controlChannelSocket;
    private final ControlChannel controlChannel;

    private DesktopConnection(Channel videoChannel, Channel audioChannel, Channel controlChannelSocket) throws IOException {
        this.videoChannel = videoChannel;
        this.audioChannel = audioChannel;
        this.controlChannelSocket = controlChannelSocket;
        controlChannel = controlChannelSocket != null
                ? new ControlChannel(controlChannelSocket.getInputStream(), controlChannelSocket.getOutputStream())
                : null;
    }

    private static String getSocketName(int scid) {
        if (scid == -1) {
            return SOCKET_NAME_PREFIX;
        }
        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control,
            boolean sendDummyByte, int tcpPort, String tcpClientIp) throws IOException {
        if (tcpPort > 0) {
            return openTcp(video, audio, control, sendDummyByte, tcpPort, tcpClientIp);
        }
        return openLocalSocket(scid, tunnelForward, video, audio, control, sendDummyByte);
    }

    private static DesktopConnection openTcp(boolean video, boolean audio, boolean control, boolean sendDummyByte, int tcpPort,
            String tcpClientIp) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        Channel videoChannel = null;
        Channel audioChannel = null;
        Channel controlChannel = null;
        try {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(tcpPort));
            serverSocket.setSoTimeout(TCP_ACCEPT_TIMEOUT_MS);

            if (video) {
                videoChannel = acceptTcpChannel(serverSocket, tcpClientIp);
                if (sendDummyByte) {
                    videoChannel.getOutputStream().write(0);
                    videoChannel.getOutputStream().flush();
                    sendDummyByte = false;
                }
            }
            if (audio) {
                audioChannel = acceptTcpChannel(serverSocket, tcpClientIp);
                if (sendDummyByte) {
                    audioChannel.getOutputStream().write(0);
                    audioChannel.getOutputStream().flush();
                    sendDummyByte = false;
                }
            }
            if (control) {
                controlChannel = acceptTcpChannel(serverSocket, tcpClientIp);
                if (sendDummyByte) {
                    controlChannel.getOutputStream().write(0);
                    controlChannel.getOutputStream().flush();
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(videoChannel);
            closeQuietly(audioChannel);
            closeQuietly(controlChannel);
            throw e;
        } finally {
            serverSocket.close();
        }
        return new DesktopConnection(videoChannel, audioChannel, controlChannel);
    }

    private static Channel acceptTcpChannel(ServerSocket serverSocket, String allowedClientIp) throws IOException {
        while (true) {
            Socket socket = serverSocket.accept();
            if (!isAllowedClient(socket.getInetAddress(), allowedClientIp)) {
                Ln.w("Rejecting connection from unexpected client " + socket.getInetAddress().getHostAddress());
                socket.close();
                continue;
            }
            return new Channel(socket);
        }
    }

    private static boolean isAllowedClient(InetAddress client, String allowedClientIp) {
        if (allowedClientIp == null || allowedClientIp.isEmpty()) {
            return true;
        }
        if (client == null) {
            return false;
        }
        String clientText = client.getHostAddress();
        int scope = clientText.indexOf('%');
        if (scope >= 0) {
            clientText = clientText.substring(0, scope);
        }
        if (allowedClientIp.equalsIgnoreCase(clientText)) {
            return true;
        }
        try {
            // Compare raw addresses so that equivalent textual forms (and IPv4-mapped IPv6) match.
            byte[] expected = toIpv4IfMapped(InetAddress.getByName(allowedClientIp).getAddress());
            byte[] actual = toIpv4IfMapped(client.getAddress());
            return Arrays.equals(expected, actual);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static byte[] toIpv4IfMapped(byte[] address) {
        if (address.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; ++i) {
            if (address[i] != 0) {
                return address;
            }
        }
        if (address[10] != (byte) 0xff || address[11] != (byte) 0xff) {
            return address;
        }
        return Arrays.copyOfRange(address, 12, 16);
    }

    private static DesktopConnection openLocalSocket(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control,
            boolean sendDummyByte) throws IOException {
        String socketName = getSocketName(scid);
        Channel videoChannel = null;
        Channel audioChannel = null;
        Channel controlChannel = null;
        try {
            if (tunnelForward) {
                try (LocalServerSocket localServerSocket = new LocalServerSocket(socketName)) {
                    if (video) {
                        videoChannel = new Channel(localServerSocket.accept());
                        if (sendDummyByte) {
                            videoChannel.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioChannel = new Channel(localServerSocket.accept());
                        if (sendDummyByte) {
                            audioChannel.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlChannel = new Channel(localServerSocket.accept());
                        if (sendDummyByte) {
                            controlChannel.getOutputStream().write(0);
                        }
                    }
                }
            } else {
                if (video) {
                    videoChannel = connectLocalSocket(socketName);
                }
                if (audio) {
                    audioChannel = connectLocalSocket(socketName);
                }
                if (control) {
                    controlChannel = connectLocalSocket(socketName);
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(videoChannel);
            closeQuietly(audioChannel);
            closeQuietly(controlChannel);
            throw e;
        }

        return new DesktopConnection(videoChannel, audioChannel, controlChannel);
    }

    private static Channel connectLocalSocket(String socketName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(socketName));
        return new Channel(localSocket);
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Preserve the exception which caused cleanup.
            }
        }
    }

    public void shutdown() throws IOException {
        IOException failure = null;
        Channel[] channels = {videoChannel, audioChannel, controlChannelSocket};
        for (Channel channel : channels) {
            if (channel != null) {
                try {
                    channel.shutdown();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        Channel[] channels = {videoChannel, audioChannel, controlChannelSocket};
        for (Channel channel : channels) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        Channel firstChannel = videoChannel != null ? videoChannel : audioChannel != null ? audioChannel : controlChannelSocket;
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];
        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        firstChannel.getOutputStream().write(buffer);
        firstChannel.getOutputStream().flush();
    }

    public OutputStream getVideoOutputStream() {
        return videoChannel != null ? videoChannel.getOutputStream() : null;
    }

    public OutputStream getAudioOutputStream() {
        return audioChannel != null ? audioChannel.getOutputStream() : null;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
