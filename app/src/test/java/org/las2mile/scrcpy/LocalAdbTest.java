package org.las2mile.scrcpy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class LocalAdbTest {
    private static boolean hasCommand(List<LocalAdb.SetupStep> steps, String part) {
        for (LocalAdb.SetupStep step : steps) {
            if (step.command.contains(part)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void rootSetupCoversPermissionsAndAdb() {
        List<LocalAdb.SetupStep> steps = LocalAdb.rootSetupSteps("org.las2mile.scrcpy", 36);
        assertTrue(hasCommand(steps, "settings put global adb_enabled 1"));
        assertTrue(hasCommand(steps, "appops set org.las2mile.scrcpy SYSTEM_ALERT_WINDOW allow"));
        assertTrue(hasCommand(steps, "pm grant org.las2mile.scrcpy android.permission.POST_NOTIFICATIONS"));
        assertTrue(hasCommand(steps, "dumpsys deviceidle whitelist +org.las2mile.scrcpy"));
        assertTrue(hasCommand(steps, "adb_allowed_connection_time 0"));
        // ADB over Wi-Fi last (restarting adbd), kept after reboot.
        String last = steps.get(steps.size() - 1).command;
        assertTrue(last.contains("persist.adb.tcp.port 5555"));
        assertTrue(last.contains("start adbd"));
        // Never touches ADB authorization itself.
        assertFalse(hasCommand(steps, "adb_keys"));
        assertFalse(hasCommand(steps, "ro.adb.secure"));
    }

    @Test
    public void rootSetupSkipsStepsTheAndroidVersionLacks() {
        List<LocalAdb.SetupStep> steps = LocalAdb.rootSetupSteps("org.las2mile.scrcpy", 21);
        assertFalse(hasCommand(steps, "POST_NOTIFICATIONS"));
        assertFalse(hasCommand(steps, "SYSTEM_ALERT_WINDOW"));
        assertFalse(hasCommand(steps, "deviceidle"));
        assertTrue(hasCommand(steps, "service.adb.tcp.port 5555"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafePackageNames() {
        LocalAdb.rootSetupSteps("x; reboot", 36);
    }

    @Test
    public void scriptReportsEveryStep() {
        List<LocalAdb.SetupStep> steps = LocalAdb.rootSetupSteps("org.las2mile.scrcpy", 36);
        String script = LocalAdb.rootSetupScript(steps);
        for (int i = 0; i < steps.size(); ++i) {
            assertTrue(script.contains("echo STEP:" + i + ":OK"));
            assertTrue(script.contains("echo STEP:" + i + ":FAIL"));
        }
    }

    @Test
    public void parsesStepOutput() {
        boolean[] ok = LocalAdb.parseSetupOutput("noise\nSTEP:0:OK\r\nSTEP:2:FAIL\nSTEP:1:OK\nSTEP:9:OK\n", 4);
        assertArrayEquals(new boolean[] {true, true, false, false}, ok);
        assertEquals(3, LocalAdb.parseSetupOutput(null, 3).length);
    }
}
