package org.las2mile.scrcpy.model;

import java.nio.ByteBuffer;

/** Video packet helpers retained for decoding H.264 codec configuration data. */
public class VideoPacket extends MediaPacket {

    public Flag flag;
    public long presentationTimeStamp;
    public byte[] data;

    public VideoPacket() {
    }

    public VideoPacket(Type type, Flag flag, long presentationTimeStamp, byte[] data) {
        this.type = type;
        this.flag = flag;
        this.presentationTimeStamp = presentationTimeStamp;
        this.data = data;
    }

    public static VideoPacket fromArray(byte[] values) {
        if (values == null || values.length < 10) {
            throw new IllegalArgumentException("Video packet is shorter than its header");
        }
        VideoPacket packet = new VideoPacket();
        packet.type = Type.getType(values[0]);
        packet.flag = Flag.getFlag(values[1]);
        byte[] timestamp = new byte[8];
        System.arraycopy(values, 2, timestamp, 0, timestamp.length);
        packet.presentationTimeStamp = ByteUtils.bytesToLong(timestamp);
        packet.data = new byte[values.length - 10];
        System.arraycopy(values, 10, packet.data, 0, packet.data.length);
        return packet;
    }

    public static byte[] toArray(Type type, Flag flag, long presentationTimeStamp, byte[] data) {
        byte[] length = ByteUtils.intToBytes(10 + data.length);
        byte[] values = new byte[14 + data.length];
        System.arraycopy(length, 0, values, 0, length.length);
        values[4] = type.getType();
        values[5] = flag.getFlag();
        byte[] timestamp = ByteUtils.longToBytes(presentationTimeStamp);
        System.arraycopy(timestamp, 0, values, 6, timestamp.length);
        System.arraycopy(data, 0, values, 14, data.length);
        return values;
    }

    public static boolean isVideoPacket(byte[] values) {
        return values != null && values.length > 0 && values[0] == Type.VIDEO.getType();
    }

    /**
     * Split Annex-B SPS/PPS data into the two codec-specific buffers expected by
     * MediaCodec. Invalid or incomplete encoder configuration fails fast instead
     * of looping forever on a damaged stream.
     */
    public static StreamSettings getStreamSettings(byte[] buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("Missing H.264 codec configuration");
        }
        int firstStart = findStartCode(buffer, 0);
        if (firstStart < 0) {
            throw new IllegalArgumentException("H.264 configuration has no Annex-B start code");
        }
        int firstLength = startCodeLength(buffer, firstStart);
        int secondStart = findStartCode(buffer, firstStart + firstLength);
        if (secondStart < 0) {
            throw new IllegalArgumentException("H.264 configuration is missing PPS");
        }

        byte[] sps = new byte[secondStart - firstStart];
        System.arraycopy(buffer, firstStart, sps, 0, sps.length);
        byte[] pps = new byte[buffer.length - secondStart];
        System.arraycopy(buffer, secondStart, pps, 0, pps.length);

        StreamSettings settings = new StreamSettings();
        settings.sps = ByteBuffer.wrap(sps);
        settings.pps = ByteBuffer.wrap(pps);
        return settings;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; ++i) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int index) {
        return data[index + 2] == 1 ? 3 : 4;
    }

    public byte[] toByteArray() {
        return toArray(type, flag, presentationTimeStamp, data);
    }

    public enum Flag {
        FRAME((byte) 0), KEY_FRAME((byte) 1), CONFIG((byte) 2), END((byte) 4);

        private final byte flag;

        Flag(byte flag) {
            this.flag = flag;
        }

        public static Flag getFlag(byte value) {
            for (Flag flag : values()) {
                if (flag.getFlag() == value) {
                    return flag;
                }
            }
            return null;
        }

        public byte getFlag() {
            return flag;
        }
    }

    public static class StreamSettings {
        public ByteBuffer pps;
        public ByteBuffer sps;
    }
}
