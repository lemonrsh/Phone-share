package com.lemonrsh.phoneshare;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies bytes both ways between a plain TCP socket and an open WebSocket, as binary frames.
 * Pings every 20 s so that Cloudflare (which drops idle connections after about 100 s) keeps
 * quiet sockets such as the ADB shell or the control channel open.
 */
final class WsPipe {
    static final long PING_INTERVAL_MS = 20_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket wsSocket;
    private final InputStream wsIn;
    private final OutputStream wsOut;
    private final boolean maskOutgoing;
    private final WsFrames.Writer writer = new WsFrames.Writer(); // guarded by writeLock
    private final Socket tcp;
    private final String name;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread pingThread;

    WsPipe(Socket wsSocket, InputStream wsIn, OutputStream wsOut, boolean maskOutgoing, Socket tcp, String name) {
        this.wsSocket = wsSocket;
        this.wsIn = wsIn;
        this.wsOut = wsOut instanceof BufferedOutputStream ? wsOut : new BufferedOutputStream(wsOut, 64 * 1024);
        this.maskOutgoing = maskOutgoing;
        this.tcp = tcp;
        this.name = name;
    }

    void start() {
        Thread up = new Thread(this::tcpToWs, name + "-up");
        Thread down = new Thread(this::wsToTcp, name + "-down");
        pingThread = new Thread(this::pingLoop, name + "-ping");
        for (Thread thread : new Thread[] {up, down, pingThread}) {
            thread.setDaemon(true);
            thread.start();
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    private void send(int opcode, byte[] data, int offset, int length) throws IOException {
        synchronized (writeLock) {
            writer.write(wsOut, opcode, data, offset, length, maskOutgoing, RANDOM);
        }
    }

    private void tcpToWs() {
        Perf.boostThread(android.os.Process.THREAD_PRIORITY_DISPLAY);
        byte[] buffer = new byte[64 * 1024];
        try {
            InputStream in = tcp.getInputStream();
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) {
                    send(WsFrames.OP_BINARY, buffer, 0, count);
                }
            }
            // Orderly end on the TCP side: tell the other end.
            send(WsFrames.OP_CLOSE, new byte[] {0x03, (byte) 0xE8}, 0, 2);
        } catch (IOException | RuntimeException ignored) {
            // closed below
        } finally {
            close();
        }
    }

    private void wsToTcp() {
        Perf.boostThread(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            OutputStream out = tcp.getOutputStream();
            WsFrames.Reader frame = new WsFrames.Reader(wsIn); // one reused buffer, no garbage per frame
            while (!closed.get()) {
                if (!frame.next()) {
                    break;
                }
                switch (frame.opcode) {
                    case WsFrames.OP_BINARY:
                    case WsFrames.OP_TEXT:
                    case WsFrames.OP_CONTINUATION:
                        if (frame.length > 0) {
                            out.write(frame.payload, 0, frame.length);
                            out.flush();
                        }
                        break;
                    case WsFrames.OP_PING:
                        send(WsFrames.OP_PONG, frame.payload, 0, frame.length);
                        break;
                    case WsFrames.OP_CLOSE:
                        try {
                            send(WsFrames.OP_CLOSE, frame.payload, 0, Math.min(frame.length, 2));
                        } catch (IOException ignored) {
                            // already gone
                        }
                        return;
                    default:
                        // pong or unknown control frame
                        break;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // closed below
        } finally {
            close();
        }
    }

    private void pingLoop() {
        byte[] empty = new byte[0];
        while (!closed.get()) {
            try {
                Thread.sleep(PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (closed.get()) {
                return;
            }
            try {
                send(WsFrames.OP_PING, empty, 0, 0);
            } catch (IOException | RuntimeException e) {
                close();
                return;
            }
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(tcp);
        closeQuietly(wsSocket);
        Thread ping = pingThread;
        if (ping != null) {
            ping.interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException | RuntimeException ignored) {
            // ignore
        }
    }
}
