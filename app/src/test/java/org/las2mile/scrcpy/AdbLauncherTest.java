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
        // "exec CLASSPATH=... app_process" fails in Android's shell: export first, then exec.
        assertTrue(command.startsWith("export CLASSPATH=/data/local/tmp/scrcpy-server.jar; exec app_process / "
                + "com.genymobile.scrcpy.Server " + ScrcpySessionOptions.SERVER_VERSION + " "));
        assertTrue(!command.contains("exec CLASSPATH="));
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
    public void sendsClientNameForTheMirroredPhone() {
        String command = AdbLauncher.buildServerCommand(new ScrcpySessionOptions(), "10.0.0.2");
        assertTrue(command.matches(".* client_name=[A-Za-z0-9._\\-]+ .*"));
        assertEquals("Infinix_X6886", AdbLauncher.sanitizeClientName("Infinix X6886"));
        assertEquals("Galaxy_S24_Ultra", AdbLauncher.sanitizeClientName("  Galaxy S24 (Ultra) "));
        assertEquals("Android", AdbLauncher.sanitizeClientName("$;`|"));
    }

    @Test
    public void md5MatchesKnownValue() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", AdbLauncher.md5Hex("abc".getBytes()));
    }
}
