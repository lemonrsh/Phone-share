package org.las2mile.scrcpy;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AdvancedOptionsTest {
    @Test
    public void specsAreConsistent() {
        Set<String> keys = new HashSet<>();
        for (AdvancedOptions.Spec spec : AdvancedOptions.SPECS) {
            assertTrue("duplicate key " + spec.key, keys.add(spec.key));
            assertTrue("unknown group " + spec.group, AdvancedOptions.GROUPS.contains(spec.group));
            if (spec.type == AdvancedOptions.Type.CHOICE) {
                assertTrue("default not a choice: " + spec.key, spec.indexOfValue(spec.defaultValue) >= 0);
            }
        }
        for (String group : AdvancedOptions.GROUPS) {
            assertFalse(AdvancedOptions.inGroup(group).isEmpty());
        }
    }

    @Test
    public void defaultsSendNoExtraArguments() {
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        assertNull(AdvancedOptions.apply(new HashMap<String, String>(), options));
        assertTrue(options.extraArgs.toString(), options.extraArgs.isEmpty());
        assertEquals("h264", options.videoCodec);
        assertEquals("aac", options.audioCodec);
        assertEquals("info", options.logLevel);
        assertTrue(options.clipboardAutosync);
        assertFalse(options.cameraSource);
        assertEquals(0, options.timeLimitSeconds);
    }

    @Test
    public void mapsValuesToServerArguments() {
        Map<String, String> values = new HashMap<>();
        values.put("video_codec", "h265");
        values.put("audio_codec", "opus");
        values.put("audio_source", "mic");
        values.put("screen_off_timeout", "300");
        values.put("crop", "1080:1080:0:420");
        values.put("capture_orientation", "@90");
        values.put("display_id", "2");
        values.put("cleanup", "false");
        values.put("time_limit", "60");
        values.put("start_app", "+com.android.settings");
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        assertNull(AdvancedOptions.apply(values, options));
        assertEquals("h265", options.videoCodec);
        assertEquals("opus", options.audioCodec);
        assertEquals("mic", options.extraArgs.get("audio_source"));
        assertEquals("300000", options.extraArgs.get("screen_off_timeout"));
        assertEquals("1080:1080:0:420", options.extraArgs.get("crop"));
        assertEquals("@90", options.extraArgs.get("capture_orientation"));
        assertEquals("2", options.extraArgs.get("display_id"));
        assertEquals("false", options.extraArgs.get("cleanup"));
        assertEquals(60, options.timeLimitSeconds);
        assertEquals("+com.android.settings", options.startApp);
    }

    @Test
    public void newDisplayDropsDisplayId() {
        Map<String, String> values = new HashMap<>();
        values.put("new_display", "true");
        values.put("new_display_size", "1920x1080/240");
        values.put("display_id", "3");
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        assertNull(AdvancedOptions.apply(values, options));
        assertEquals("1920x1080/240", options.extraArgs.get("new_display"));
        assertFalse(options.extraArgs.containsKey("display_id"));
    }

    @Test
    public void rejectsShellInjectionAndBadValues() {
        String[][] bad = {
                {"video_encoder", "x; reboot"},
                {"video_codec_options", "a b"},
                {"crop", "100:100"},
                {"angle", "ninety"},
                {"camera_size", "$(id)"},
                {"new_display_size", "big"},
        };
        for (String[] entry : bad) {
            Map<String, String> values = new HashMap<>();
            values.put(entry[0], entry[1]);
            if (entry[0].equals("new_display_size")) {
                values.put("new_display", "true");
            }
            assertNotNull(entry[0] + "=" + entry[1], AdvancedOptions.apply(values, new ScrcpySessionOptions()));
        }
    }

    @Test
    public void serverCommandIncludesOptionsBoxValues() {
        Map<String, String> values = new HashMap<>();
        values.put("video_codec", "av1");
        values.put("audio_codec", "flac");
        values.put("video_source", "camera");
        values.put("camera_facing", "back");
        values.put("log_level", "debug");
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        assertNull(AdvancedOptions.apply(values, options));
        // A reserved key smuggled in must be ignored.
        options.extraArgs.put("tcp_port", "1");
        String command = AdbLauncher.buildServerCommand(options, "10.0.0.2");
        assertTrue(command, command.contains(" video_codec=av1 "));
        assertTrue(command, command.contains(" audio_codec=flac "));
        assertTrue(command, command.contains(" log_level=debug "));
        assertTrue(command, command.contains(" video_source=camera"));
        assertTrue(command, command.contains(" camera_facing=back"));
        assertTrue(command, command.contains(" clipboard_autosync=true "));
        assertFalse(command, command.contains("tcp_port=1"));
        assertTrue(options.cameraSource);
    }

    @Test
    public void normalAudioDupForcesPlaybackSource() {
        Map<String, String> values = new HashMap<>();
        values.put("audio_source", "mic");
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        options.audioEnabled = true;
        assertNull(AdvancedOptions.apply(values, options));
        AdvancedOptions.applyNormal(options, true, true);
        assertEquals("playback", options.extraArgs.get("audio_source"));
        assertEquals("true", options.extraArgs.get("audio_dup"));
        assertEquals("true", options.extraArgs.get("keep_active"));
        String command = AdbLauncher.buildServerCommand(options, "10.0.0.2");
        assertTrue(command, command.contains(" audio_dup=true"));
        assertTrue(command, command.contains(" audio_source=playback"));
        assertFalse(command, command.contains("audio_source=mic"));
    }

    @Test
    public void audioDupNeedsAudio() {
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        options.audioEnabled = false;
        assertNull(AdvancedOptions.apply(new HashMap<String, String>(), options));
        AdvancedOptions.applyNormal(options, true, false);
        assertFalse(options.extraArgs.containsKey("audio_dup"));
        assertFalse(options.extraArgs.containsKey("keep_active"));
    }

    @Test
    public void movedOptionsAreNotInTheAdvancedBox() {
        assertNull(AdvancedOptions.find("audio_dup"));
        assertNull(AdvancedOptions.find("keep_active"));
    }
}
