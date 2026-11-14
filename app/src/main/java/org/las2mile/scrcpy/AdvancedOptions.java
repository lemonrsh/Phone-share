package org.las2mile.scrcpy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Catalogue of the additional scrcpy v5.0.1 options shown in the options box,
 * on top of the original quality/control switches. Each entry knows how it is
 * stored (a string preference), how it is shown, and how it maps to a server
 * argument or to viewer behaviour. Pure Java so that it is covered by JVM tests.
 *
 * <p>Options that only make sense for the desktop client (window flags,
 * recording, V4L2, USB/OTG/HID modes) are not listed.
 */
public final class AdvancedOptions {
    public enum Type { SWITCH, CHOICE, TEXT, NUMBER }

    public static final String GROUP_VIDEO = "Video";
    public static final String GROUP_AUDIO = "Audio";
    public static final String GROUP_CONTROL = "Control & behaviour";
    public static final String GROUP_CAPTURE = "Display & capture";
    public static final String GROUP_CAMERA = "Camera";
    public static final String GROUP_VIRTUAL_DISPLAY = "Virtual display";
    public static final String GROUP_ADVANCED = "Advanced";

    public static final List<String> GROUPS = Collections.unmodifiableList(java.util.Arrays.asList(
            GROUP_VIDEO, GROUP_AUDIO, GROUP_CONTROL, GROUP_CAPTURE, GROUP_CAMERA, GROUP_VIRTUAL_DISPLAY,
            GROUP_ADVANCED));

    /** Preference keys are prefixed so that they never collide with the original settings. */
    public static final String PREF_PREFIX = "adv_";

    // Keys handled by the viewer itself rather than passed through verbatim.
    static final String KEY_VIDEO_CODEC = "video_codec";
    static final String KEY_AUDIO_CODEC = "audio_codec";
    static final String KEY_VIDEO_SOURCE = "video_source";
    static final String KEY_CLIPBOARD = "clipboard_autosync";
    static final String KEY_START_APP = "start_app";
    static final String KEY_TIME_LIMIT = "time_limit";
    static final String KEY_SCREEN_OFF_TIMEOUT = "screen_off_timeout";
    static final String KEY_NEW_DISPLAY = "new_display";
    static final String KEY_NEW_DISPLAY_SIZE = "new_display_size";
    static final String KEY_DISPLAY_ID = "display_id";
    static final String KEY_LOG_LEVEL = "log_level";

    /** Characters allowed in values that end up on the target's shell command line. */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:,=/@+\\-]*");
    private static final int MAX_VALUE_LENGTH = 200;

    public static final class Spec {
        public final String key;
        public final String group;
        public final Type type;
        public final String label;
        public final String help;
        public final String defaultValue;
        public final String[] choiceLabels;
        public final String[] choiceValues;
        /** Server argument name, or null when the viewer handles the option. */
        public final String serverArg;
        /** Value equal to the server default: no argument is sent for it. */
        public final String serverDefault;
        /** Example shown as the hint of text fields. */
        public final String hint;

        Spec(String key, String group, Type type, String label, String help, String defaultValue,
             String[] choiceLabels, String[] choiceValues, String serverArg, String serverDefault, String hint) {
            this.key = key;
            this.group = group;
            this.type = type;
            this.label = label;
            this.help = help;
            this.defaultValue = defaultValue;
            this.choiceLabels = choiceLabels;
            this.choiceValues = choiceValues;
            this.serverArg = serverArg;
            this.serverDefault = serverDefault;
            this.hint = hint;
        }

        public String prefKey() {
            return PREF_PREFIX + key;
        }

        public int indexOfValue(String value) {
            if (choiceValues == null) {
                return -1;
            }
            for (int i = 0; i < choiceValues.length; ++i) {
                if (choiceValues[i].equals(value)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public static final List<Spec> SPECS;

    static {
        List<Spec> s = new ArrayList<>();

        // ------------------------------------------------------------ video
        s.add(choice(KEY_VIDEO_CODEC, GROUP_VIDEO, "Video codec",
                "H.265 and AV1 give better quality per bit but need encoder support on the target and a "
                        + "decoder on this phone.",
                "h264", new String[] {"H.264 (most compatible)", "H.265 / HEVC", "AV1"},
                new String[] {"h264", "h265", "av1"}, null, null));
        s.add(text("video_encoder", GROUP_VIDEO, "Video encoder name",
                "Force a specific encoder of the target (see the Target log). Empty = automatic.",
                "video_encoder", "OMX.qcom.video.encoder.avc"));
        s.add(text("video_codec_options", GROUP_VIDEO, "Video codec options",
                "MediaFormat keys for the encoder, comma separated.",
                "video_codec_options", "profile=1,level=4096"));
        s.add(sw("downsize_on_error", GROUP_VIDEO, "Downsize on encoder error",
                "Retry with a smaller size when the encoder fails.", true, "downsize_on_error"));
        s.add(sw("ignore_video_encoder_constraints", GROUP_VIDEO, "Ignore encoder constraints",
                "Use the requested size/frame rate even if the encoder says it is unsupported.", false,
                "ignore_video_encoder_constraints"));
        s.add(choice("min_size_alignment", GROUP_VIDEO, "Video size alignment",
                "Some encoders need the width and height to be a multiple of 8 or 16.",
                "1", new String[] {"Default", "2", "4", "8", "16"},
                new String[] {"1", "2", "4", "8", "16"}, "min_size_alignment", "1"));

        // ------------------------------------------------------------ audio
        s.add(choice(KEY_AUDIO_CODEC, GROUP_AUDIO, "Audio codec",
                "Used when remote audio is enabled. Raw needs the most bandwidth; Opus the least.",
                "aac", new String[] {"AAC", "Opus", "FLAC (lossless)", "Raw PCM"},
                new String[] {"aac", "opus", "flac", "raw"}, null, null));
        s.add(choice("audio_source", GROUP_AUDIO, "Audio source",
                "What to capture on the target. \"Playback\" (Android 13+) can keep audio playing there too.",
                "output",
                new String[] {"Device output", "App playback (Android 13+)", "Microphone",
                        "Microphone (unprocessed)", "Microphone (camcorder)", "Microphone (voice recognition)",
                        "Microphone (voice communication)", "Voice call", "Voice call (uplink)",
                        "Voice call (downlink)", "Voice performance"},
                new String[] {"output", "playback", "mic", "mic-unprocessed", "mic-camcorder",
                        "mic-voice-recognition", "mic-voice-communication", "voice-call", "voice-call-uplink",
                        "voice-call-downlink", "voice-performance"},
                "audio_source", "output"));
        s.add(sw("audio_dup", GROUP_AUDIO, "Keep playing audio on the target",
                "Duplicate audio instead of muting it on the target. Needs the \"App playback\" source.",
                false, "audio_dup"));
        s.add(choice("audio_bit_rate", GROUP_AUDIO, "Audio bitrate", "Ignored for FLAC and raw.",
                "128000", new String[] {"64 kbps", "96 kbps", "128 kbps (default)", "192 kbps", "256 kbps", "320 kbps"},
                new String[] {"64000", "96000", "128000", "192000", "256000", "320000"},
                "audio_bit_rate", "128000"));
        s.add(text("audio_encoder", GROUP_AUDIO, "Audio encoder name", "Empty = automatic.",
                "audio_encoder", "c2.android.aac.encoder"));
        s.add(text("audio_codec_options", GROUP_AUDIO, "Audio codec options",
                "MediaFormat keys for the audio encoder, comma separated.", "audio_codec_options",
                "flac-compression-level=8"));

        // ------------------------------------------------------------ control & behaviour
        s.add(sw(KEY_CLIPBOARD, GROUP_CONTROL, "Clipboard sync",
                "Copy on one device, paste on the other (needs control).", true, null));
        s.add(text(KEY_START_APP, GROUP_CONTROL, "Start an app on connect",
                "Package name, or ?name to search by app name. Prefix with + to force-stop it first.",
                null, "com.android.settings"));
        s.add(number(KEY_SCREEN_OFF_TIMEOUT, GROUP_CONTROL, "Screen-off timeout (seconds)",
                "Temporarily change the target's screen timeout while connected. Empty = unchanged.",
                null, "300"));
        s.add(sw("keep_active", GROUP_CONTROL, "Keep the target active",
                "Periodically signal user activity so the target never locks while mirroring.", false,
                "keep_active"));
        s.add(number(KEY_TIME_LIMIT, GROUP_CONTROL, "Time limit (seconds)",
                "Disconnect automatically after this time. Empty = no limit.", null, "600"));
        s.add(sw("cleanup", GROUP_CONTROL, "Clean up on exit",
                "Restore show-touches/stay-awake/power settings on the target when done.", true, "cleanup"));

        // ------------------------------------------------------------ display & capture
        s.add(choice(KEY_VIDEO_SOURCE, GROUP_CAPTURE, "Video source",
                "Mirror the screen or one of the target's cameras (Android 12+). Touch is off for cameras.",
                "display", new String[] {"Screen", "Camera"}, new String[] {"display", "camera"},
                "video_source", "display"));
        s.add(number(KEY_DISPLAY_ID, GROUP_CAPTURE, "Display ID",
                "Mirror another display of the target (see the Target log). Empty = main display.",
                "display_id", "0"));
        s.add(choice("capture_orientation", GROUP_CAPTURE, "Capture orientation",
                "Rotate or flip the captured video. \"Lock\" keeps the orientation when the target rotates.",
                "", new String[] {"Automatic", "0°", "90°", "180°", "270°", "Flip 0°", "Flip 90°", "Flip 180°",
                        "Flip 270°", "Lock current", "Lock 0°", "Lock 90°", "Lock 180°", "Lock 270°"},
                new String[] {"", "0", "90", "180", "270", "flip0", "flip90", "flip180", "flip270", "@", "@0",
                        "@90", "@180", "@270"},
                "capture_orientation", ""));
        s.add(number("angle", GROUP_CAPTURE, "Rotation angle (degrees)",
                "Rotate the content by any angle, clockwise. Empty = none.", "angle", "15"));
        s.add(text("crop", GROUP_CAPTURE, "Crop",
                "Mirror only part of the screen: width:height:x:y in device pixels.", "crop", "1224:1440:0:0"));

        // ------------------------------------------------------------ camera
        s.add(text("camera_id", GROUP_CAMERA, "Camera ID", "Empty = first camera matching the facing below.",
                "camera_id", "0"));
        s.add(choice("camera_facing", GROUP_CAMERA, "Camera facing", null, "",
                new String[] {"Any", "Front", "Back", "External"}, new String[] {"", "front", "back", "external"},
                "camera_facing", ""));
        s.add(text("camera_size", GROUP_CAMERA, "Camera size", "Exact capture size, e.g. 1920x1080.",
                "camera_size", "1920x1080"));
        s.add(text("camera_ar", GROUP_CAMERA, "Camera aspect ratio",
                "Pick the largest size with this ratio: sensor, 4:3, 16:9 or 1.6.", "camera_ar", "4:3"));
        s.add(number("camera_fps", GROUP_CAMERA, "Camera frame rate", "Empty = camera default.", "camera_fps",
                "60"));
        s.add(number("camera_zoom", GROUP_CAMERA, "Camera zoom", "Initial zoom factor. Empty = 1.",
                "camera_zoom", "2.0"));
        s.add(sw("camera_high_speed", GROUP_CAMERA, "High-speed capture",
                "Allow high frame rates (e.g. 120/240 fps) on supported cameras.", false, "camera_high_speed"));
        s.add(sw("camera_torch", GROUP_CAMERA, "Turn the torch on", null, false, "camera_torch"));

        // ------------------------------------------------------------ virtual display
        s.add(sw(KEY_NEW_DISPLAY, GROUP_VIRTUAL_DISPLAY, "Mirror a new virtual display",
                "Create a separate display on the target (Android 10+) instead of mirroring its screen.",
                false, null));
        s.add(text(KEY_NEW_DISPLAY_SIZE, GROUP_VIRTUAL_DISPLAY, "Virtual display size",
                "width x height and optionally /dpi. Empty = same as the main display.", null, "1920x1080/240"));
        s.add(sw("vd_destroy_content", GROUP_VIRTUAL_DISPLAY, "Close its apps on disconnect",
                "Otherwise apps move to the main display when the virtual display is removed.", true,
                "vd_destroy_content"));
        s.add(sw("vd_system_decorations", GROUP_VIRTUAL_DISPLAY, "System decorations",
                "Show the launcher and system bars on the virtual display.", true, "vd_system_decorations"));
        s.add(choice("display_ime_policy", GROUP_VIRTUAL_DISPLAY, "Keyboard (IME) location", null, "",
                new String[] {"Default", "On the mirrored display", "On the main display", "Hidden"},
                new String[] {"", "local", "fallback", "hide"}, "display_ime_policy", ""));

        // ------------------------------------------------------------ advanced
        s.add(choice(KEY_LOG_LEVEL, GROUP_ADVANCED, "Server log level",
                "More detail in Logs › Connection log.", "info",
                new String[] {"Verbose", "Debug", "Info", "Warning", "Error"},
                new String[] {"verbose", "debug", "info", "warn", "error"}, null, null));

        SPECS = Collections.unmodifiableList(s);
    }

    private AdvancedOptions() {
    }

    public static Spec find(String key) {
        for (Spec spec : SPECS) {
            if (spec.key.equals(key)) {
                return spec;
            }
        }
        return null;
    }

    public static List<Spec> inGroup(String group) {
        List<Spec> result = new ArrayList<>();
        for (Spec spec : SPECS) {
            if (spec.group.equals(group)) {
                result.add(spec);
            }
        }
        return result;
    }

    /** Values for every option, filled with defaults; {@code stored} maps option keys to saved values. */
    public static Map<String, String> withDefaults(Map<String, String> stored) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Spec spec : SPECS) {
            String value = stored == null ? null : stored.get(spec.key);
            values.put(spec.key, value == null ? spec.defaultValue : value);
        }
        return values;
    }

    /**
     * Validates the values and applies them to {@code options}. Returns an error message for the
     * user, or null on success.
     */
    public static String apply(Map<String, String> stored, ScrcpySessionOptions options) {
        Map<String, String> values = withDefaults(stored);
        Map<String, String> args = new LinkedHashMap<>();

        for (Spec spec : SPECS) {
            String value = values.get(spec.key);
            value = value == null ? "" : value.trim();
            if (value.length() > MAX_VALUE_LENGTH) {
                return spec.label + ": the value is too long.";
            }
            switch (spec.type) {
                case SWITCH:
                    value = Boolean.toString(Boolean.parseBoolean(value));
                    break;
                case CHOICE:
                    if (spec.indexOfValue(value) < 0) {
                        value = spec.defaultValue;
                    }
                    break;
                case NUMBER:
                    if (!value.isEmpty()) {
                        try {
                            double number = Double.parseDouble(value);
                            if (Double.isNaN(number) || Double.isInfinite(number)) {
                                throw new NumberFormatException();
                            }
                        } catch (NumberFormatException e) {
                            return spec.label + ": \"" + value + "\" is not a number.";
                        }
                    }
                    break;
                case TEXT:
                default:
                    break;
            }
            if (!SAFE_VALUE.matcher(value).matches()) {
                return spec.label + ": only letters, digits and . _ : , = / @ + - are allowed.";
            }
            values.put(spec.key, value);
            if (spec.serverArg != null && !value.isEmpty() && !value.equals(spec.serverDefault)) {
                args.put(spec.serverArg, value);
            }
        }

        options.videoCodec = values.get(KEY_VIDEO_CODEC);
        options.audioCodec = values.get(KEY_AUDIO_CODEC);
        options.clipboardAutosync = Boolean.parseBoolean(values.get(KEY_CLIPBOARD));
        options.startApp = values.get(KEY_START_APP);
        options.cameraSource = "camera".equals(values.get(KEY_VIDEO_SOURCE));
        options.logLevel = values.get(KEY_LOG_LEVEL);

        String limit = values.get(KEY_TIME_LIMIT);
        options.timeLimitSeconds = 0;
        if (!limit.isEmpty()) {
            int seconds = (int) Math.round(Double.parseDouble(limit));
            if (seconds <= 0) {
                return "Time limit must be a positive number of seconds.";
            }
            options.timeLimitSeconds = seconds;
        }

        String screenOff = values.get(KEY_SCREEN_OFF_TIMEOUT);
        if (!screenOff.isEmpty()) {
            long seconds = Math.round(Double.parseDouble(screenOff));
            if (seconds <= 0 || seconds > Integer.MAX_VALUE / 1000) {
                return "Screen-off timeout must be a positive number of seconds.";
            }
            // The server takes milliseconds (like the desktop client sends).
            args.put("screen_off_timeout", Long.toString(seconds * 1000));
        }

        String displayId = values.get(KEY_DISPLAY_ID);
        if (!displayId.isEmpty()) {
            try {
                int id = Integer.parseInt(displayId);
                if (id < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                return "Display ID must be a whole number.";
            }
        }

        if (Boolean.parseBoolean(values.get(KEY_NEW_DISPLAY))) {
            // The server refuses display_id together with new_display.
            args.remove("display_id");
            String size = values.get(KEY_NEW_DISPLAY_SIZE);
            if (!size.isEmpty() && !size.matches("(\\d+x\\d+)?(/\\d+)?")) {
                return "Virtual display size must look like 1920x1080, 1920x1080/240 or /240.";
            }
            args.put("new_display", size);
        }

        String crop = values.get("crop");
        if (!crop.isEmpty() && !crop.matches("\\d+:\\d+:\\d+:\\d+")) {
            return "Crop must be width:height:x:y, for example 1224:1440:0:0.";
        }

        String startApp = options.startApp;
        if (startApp != null && startApp.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            return "The app to start has a too long name.";
        }

        options.extraArgs.clear();
        options.extraArgs.putAll(args);
        return null;
    }

    /** Lower-case, trimmed; used for codec names coming from preferences. */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ builders

    private static Spec sw(String key, String group, String label, String help, boolean def, String serverArg) {
        return new Spec(key, group, Type.SWITCH, label, help, Boolean.toString(def), null, null, serverArg,
                serverArg == null ? null : Boolean.toString(def), null);
    }

    private static Spec choice(String key, String group, String label, String help, String def, String[] labels,
                               String[] values, String serverArg, String serverDefault) {
        if (labels.length != values.length) {
            throw new IllegalArgumentException("Choice labels/values mismatch for " + key);
        }
        return new Spec(key, group, Type.CHOICE, label, help, def, labels, values, serverArg, serverDefault, null);
    }

    private static Spec text(String key, String group, String label, String help, String serverArg, String hint) {
        return new Spec(key, group, Type.TEXT, label, help, "", null, null, serverArg, "", hint);
    }

    private static Spec number(String key, String group, String label, String help, String serverArg, String hint) {
        return new Spec(key, group, Type.NUMBER, label, help, "", null, null, serverArg, "", hint);
    }
}
