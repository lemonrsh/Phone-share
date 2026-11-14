package com.lemonrsh.phoneshare;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The share tunnel end to end on the loopback interface: client → WebSocket bridge → TCP echo server. */
public class CloudTunnelTest {
    private ServerSocket echoServer;
    private WsBridgeServer bridge;

    @Before
    public void setUp() throws IOException {
        echoServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            while (true) {
                final Socket socket;
                try {
                    socket = echoServer.accept();
                } catch (IOException e) {
                    return;
                }
                new Thread(() -> {
                    try (Socket s = socket) {
                        InputStream in = s.getInputStream();
                        OutputStream out = s.getOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = in.read(buffer)) >= 0) {
                            out.write(buffer, 0, count);
                            out.flush();
                        }
                    } catch (IOException ignored) {
                        // done
                    }
                }).start();
            }
        });
        thread.setDaemon(true);
        thread.start();

        Map<String, Integer> targets = new HashMap<>();
        targets.put("echo", echoServer.getLocalPort());
        targets.put("closed", closedPort());
        bridge = new WsBridgeServer("123456", targets);
        bridge.start();
    }

    @After
    public void tearDown() throws IOException {
        bridge.stop();
        echoServer.close();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private Socket open(String path) throws IOException {
        Socket socket = new Socket();
        CloudTunnel.connectVia(socket, "127.0.0.1", bridge.getPort(), false, path, 5000, "test");
        socket.setSoTimeout(10_000);
        return socket;
    }

    @Test
    public void bytesGoBothWays() throws IOException {
        byte[] data = new byte[300_000];
        new Random(42).nextBytes(data);
        try (Socket socket = open("/echo?pin=123456")) {
            final OutputStream out = socket.getOutputStream();
            Thread writer = new Thread(() -> {
                try {
                    // Mixed sizes: small (<126), 16-bit and 64-bit length frames.
                    int offset = 0;
                    int[] sizes = {1, 100, 125, 126, 65_535, 65_536, 70_000};
                    int i = 0;
                    while (offset < data.length) {
                        int size = Math.min(sizes[i++ % sizes.length], data.length - offset);
                        out.write(data, offset, size);
                        out.flush();
                        offset += size;
                    }
                } catch (IOException ignored) {
                    // the read below fails
                }
            });
            writer.start();
            byte[] received = new byte[data.length];
            new DataInputStream(socket.getInputStream()).readFully(received);
            assertArrayEquals(data, received);
        }
    }

    @Test
    public void wrongPinIsRejected() throws IOException {
        try {
            open("/echo?pin=000000").close();
            fail("expected a TunnelException");
        } catch (CloudTunnel.TunnelException e) {
            assertEquals(403, e.status);
            assertTrue(e.getMessage().contains("Wrong PIN"));
        }
    }

    @Test
    public void closedTargetPortIsReported() throws IOException {
        try {
            open("/closed?pin=123456").close();
            fail("expected a TunnelException");
        } catch (CloudTunnel.TunnelException e) {
            assertEquals(502, e.status);
        }
    }

    @Test
    public void unknownTargetIsRejected() throws IOException {
        try {
            open("/nothing?pin=123456").close();
            fail("expected a TunnelException");
        } catch (CloudTunnel.TunnelException e) {
            assertEquals(404, e.status);
        }
    }

    @Test
    public void adbOffMessageIsFriendly() {
        String message = CloudTunnel.describeStatus(502, "adb is not listening on port 5555 of the shared phone");
        assertTrue(message.contains("ADB over Wi-Fi"));
    }

    @Test
    public void framesRoundTrip() throws IOException {
        byte[] payload = "hello tunnel".getBytes("UTF-8");
        for (boolean mask : new boolean[] {false, true}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            WsFrames.write(out, WsFrames.OP_BINARY, payload, 0, payload.length, mask, new Random(1));
            WsFrames.Frame frame = WsFrames.read(new ByteArrayInputStream(out.toByteArray()));
            assertNotNull(frame);
            assertEquals(WsFrames.OP_BINARY, frame.opcode);
            assertTrue(Arrays.equals(payload, frame.payload));
        }
        assertNull(WsFrames.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void acceptKeyMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
        assertEquals("", WsFrames.base64(new byte[0]));
        assertEquals("Zm9vYmFy", WsFrames.base64("foobar".getBytes()));
        assertEquals("Zm9vYg==", WsFrames.base64("foob".getBytes()));
    }

    @Test
    public void findsTheQuickTunnelAddressInCloudflaredOutput() {
        assertEquals("blue-river-cat-dog.trycloudflare.com", Cloudflared.findAddress(
                "2026-11-14T10:00:00Z INF |  https://blue-river-cat-dog.trycloudflare.com                          |"));
        assertNull(Cloudflared.findAddress("INF Requesting new quick Tunnel on trycloudflare.com..."));
        assertNull(Cloudflared.findAddress("ERR failed to reach https://api.trycloudflare.com/tunnel"));
    }

    @Test
    public void parsesShareCodes() {
        HostAddress address = HostAddress.parse(" https://Blue-River-Cat.trycloudflare.com/#482913 ");
        assertNotNull(address);
        assertEquals("blue-river-cat.trycloudflare.com", address.host);
        assertEquals("482913", address.pin);
        assertTrue(address.isTunnel());
        assertEquals("482913", CloudTunnel.pinFor("blue-river-cat.trycloudflare.com"));
        assertNull(HostAddress.parse("192.168.1.4#123456"));
        assertNull(HostAddress.parse("x.trycloudflare.com#12ab"));
        HostAddress plain = HostAddress.parse("192.168.1.4");
        assertNull(plain.pin);
        assertTrue(!plain.isTunnel());
    }

    @Test
    public void explainsSignalExitCodes() {
        assertTrue(Cloudflared.describeExit(139).contains("SIGSEGV"));
        assertEquals("", Cloudflared.describeExit(1));
    }

    @Test
    public void pinsAreSixDigits() {
        for (int i = 0; i < 50; ++i) {
            assertTrue(ShareService.newPin().matches("[0-9]{6}"));
        }
    }
}
