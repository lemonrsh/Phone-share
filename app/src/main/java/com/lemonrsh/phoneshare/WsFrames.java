package com.lemonrsh.phoneshare;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Minimal RFC 6455 WebSocket framing and HTTP/1.1 handshake helpers, pure Java so that both
 * sides of the Cloudflare share tunnel can be unit tested on the JVM. Only what the tunnel
 * needs: binary data, ping/pong and close.
 */
final class WsFrames {
    static final int OP_CONTINUATION = 0;
    static final int OP_TEXT = 1;
    static final int OP_BINARY = 2;
    static final int OP_CLOSE = 8;
    static final int OP_PING = 9;
    static final int OP_PONG = 10;

    static final int MAX_PAYLOAD = 8 * 1024 * 1024;
    private static final int MAX_HEAD = 16 * 1024;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final char[] BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** An HTTP request or response head: the first line and lower-case header names. */
    static final class Head {
        final String firstLine;
        final Map<String, String> headers;

        Head(String firstLine, Map<String, String> headers) {
            this.firstLine = firstLine;
            this.headers = headers;
        }

        String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.ROOT));
            return value == null ? "" : value;
        }
    }

    private WsFrames() {
    }

    /** Writes one unfragmented frame. Clients must mask, servers must not. Not thread-safe. */
    static void write(OutputStream out, int opcode, byte[] data, int offset, int length, boolean mask, Random random)
            throws IOException {
        byte[] header = new byte[14];
        int size = 0;
        header[size++] = (byte) (0x80 | opcode);
        int maskBit = mask ? 0x80 : 0;
        if (length < 126) {
            header[size++] = (byte) (maskBit | length);
        } else if (length <= 0xFFFF) {
            header[size++] = (byte) (maskBit | 126);
            header[size++] = (byte) (length >>> 8);
            header[size++] = (byte) length;
        } else {
            header[size++] = (byte) (maskBit | 127);
            header[size++] = 0;
            header[size++] = 0;
            header[size++] = 0;
            header[size++] = 0;
            header[size++] = (byte) (length >>> 24);
            header[size++] = (byte) (length >>> 16);
            header[size++] = (byte) (length >>> 8);
            header[size++] = (byte) length;
        }
        if (!mask) {
            out.write(header, 0, size);
            out.write(data, offset, length);
            out.flush();
            return;
        }
        byte[] key = new byte[4];
        random.nextBytes(key);
        System.arraycopy(key, 0, header, size, 4);
        size += 4;
        byte[] masked = new byte[length];
        for (int i = 0; i < length; ++i) {
            masked[i] = (byte) (data[offset + i] ^ key[i & 3]);
        }
        out.write(header, 0, size);
        out.write(masked, 0, length);
        out.flush();
    }

    /** Reads one frame (masked or not), or returns null at end of stream. */
    static Frame read(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return null;
        }
        DataInputStream in = input instanceof DataInputStream ? (DataInputStream) input : new DataInputStream(input);
        int second = in.readUnsignedByte();
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = in.readUnsignedShort();
        } else if (length == 127) {
            length = in.readLong();
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("WebSocket frame too large: " + length);
        }
        byte[] key = null;
        if (masked) {
            key = new byte[4];
            in.readFully(key);
        }
        byte[] payload = new byte[(int) length];
        in.readFully(payload);
        if (key != null) {
            for (int i = 0; i < payload.length; ++i) {
                payload[i] ^= key[i & 3];
            }
        }
        return new Frame(opcode, payload);
    }

    /** Reads an HTTP head up to the empty line. Reads byte by byte: nothing after it is consumed. */
    static Head readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Connection closed during the HTTP handshake");
            }
            buffer.write(b);
            if (buffer.size() > MAX_HEAD) {
                throw new IOException("HTTP head too large");
            }
            if ((matched == 0 || matched == 2) && b == '\r') {
                ++matched;
            } else if ((matched == 1 || matched == 3) && b == '\n') {
                ++matched;
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; ++i) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        return new Head(lines.length > 0 ? lines[0] : "", headers);
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return base64(sha1.digest((key.trim() + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Standard Base64 with padding (android.util.Base64 is not usable in JVM tests, java.util.Base64 needs API 26). */
    static String base64(byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            out.append(BASE64[b0 >>> 2]);
            out.append(BASE64[((b0 & 3) << 4) | (b1 >>> 4)]);
            out.append(i + 1 < data.length ? BASE64[((b1 & 15) << 2) | (b2 >>> 6)] : '=');
            out.append(i + 2 < data.length ? BASE64[b2 & 63] : '=');
        }
        return out.toString();
    }
}
