package org.las2mile.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdbLauncherTest {
    @Test
    public void buildsServerCommand() {
        ScrcpySessionOptions options = new ScrcpySessionOptions();
        options.noControl = true;
        options.audioEnabled = true;
        options.maxSize = 1920;
        String command = AdbLauncher.buildServerCommand(options, "192.168.1.5");
        assertTrue(command.startsWith("CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / "
                + "com.genymobile.scrcpy.Server " + ScrcpySessionOptions.SERVER_VERSION + " "));
        assertTrue(command.contains(" tcp_client=192.168.1.5 "));
        assertTrue(command.contains(" tcp_port=7007 "));
        assertTrue(command.contains(" audio=true "));
        assertTrue(command.contains(" control=false "));
        // power_on needs control
        assertTrue(command.contains(" power_on=false "));
        assertTrue(command.contains(" max_size=1920 "));
        assertTrue(command.contains(" clipboard_autosync=false "));
    }

    @Test
    public void md5MatchesKnownValue() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", AdbLauncher.md5Hex("abc".getBytes()));
    }
}
