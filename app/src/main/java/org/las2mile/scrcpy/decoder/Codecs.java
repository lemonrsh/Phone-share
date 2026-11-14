package org.las2mile.scrcpy.decoder;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** Codec names, scrcpy stream IDs and local decoder availability. */
public final class Codecs {
    private static final String TAG = "Codecs";

    // IDs written by the server at the start of each stream (see VideoCodec/AudioCodec there).
    public static final int VIDEO_H264 = 0x68_32_36_34;
    public static final int VIDEO_H265 = 0x68_32_36_35;
    public static final int VIDEO_AV1 = 0x00_61_76_31;
    public static final int AUDIO_OPUS = 0x6f_70_75_73;
    public static final int AUDIO_AAC = 0x00_61_61_63;
    public static final int AUDIO_FLAC = 0x66_6c_61_63;
    public static final int AUDIO_RAW = 0x00_72_61_77;

    private static final Map<String, Boolean> DECODER_CACHE = new HashMap<>();

    private Codecs() {
    }

    public static String videoMime(String name) {
        if ("h265".equals(name)) {
            return MediaFormat.MIMETYPE_VIDEO_HEVC;
        }
        if ("av1".equals(name)) {
            return "video/av01"; // MediaFormat.MIMETYPE_VIDEO_AV1 (API 29)
        }
        return MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    public static int videoId(String name) {
        if ("h265".equals(name)) {
            return VIDEO_H265;
        }
        if ("av1".equals(name)) {
            return VIDEO_AV1;
        }
        return VIDEO_H264;
    }

    public static int audioId(String name) {
        if ("opus".equals(name)) {
            return AUDIO_OPUS;
        }
        if ("flac".equals(name)) {
            return AUDIO_FLAC;
        }
        if ("raw".equals(name)) {
            return AUDIO_RAW;
        }
        return AUDIO_AAC;
    }

    public static String audioMime(int id) {
        switch (id) {
            case AUDIO_OPUS:
                return MediaFormat.MIMETYPE_AUDIO_OPUS;
            case AUDIO_FLAC:
                return MediaFormat.MIMETYPE_AUDIO_FLAC;
            case AUDIO_RAW:
                return MediaFormat.MIMETYPE_AUDIO_RAW;
            case AUDIO_AAC:
            default:
                return MediaFormat.MIMETYPE_AUDIO_AAC;
        }
    }

    public static String describeId(int id) {
        switch (id) {
            case VIDEO_H264:
                return "H.264";
            case VIDEO_H265:
                return "H.265";
            case VIDEO_AV1:
                return "AV1";
            case AUDIO_OPUS:
                return "Opus";
            case AUDIO_AAC:
                return "AAC";
            case AUDIO_FLAC:
                return "FLAC";
            case AUDIO_RAW:
                return "raw";
            default:
                return "0x" + Integer.toHexString(id);
        }
    }

    /** Whether this phone has a decoder for {@code mime} (cached, the codec list is slow to query). */
    public static synchronized boolean hasDecoder(String mime) {
        if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) {
            return true;
        }
        Boolean cached = DECODER_CACHE.get(mime);
        if (cached != null) {
            return cached;
        }
        boolean found = false;
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo info : infos) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not query the decoders", e);
            found = true; // do not block the user on a broken codec list
        }
        DECODER_CACHE.put(mime, found);
        return found;
    }
}
