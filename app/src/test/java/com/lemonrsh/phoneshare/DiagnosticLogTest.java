package com.lemonrsh.phoneshare;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticLogTest {
    @Before
    public void setUp() {
        DiagnosticLog.clear();
    }

    @After
    public void tearDown() {
        DiagnosticLog.clear();
    }

    @Test
    public void recordsStepsAndShellOutputInOrder() {
        assertTrue(DiagnosticLog.isEmpty());
        DiagnosticLog.i("adb", "Shell opened");
        DiagnosticLog.shellOutput("[server] INFO: Dev");
        DiagnosticLog.shellOutput("ice: Pixel\r\nKill");
        DiagnosticLog.i("client", "Session ended");
        String[] lines = DiagnosticLog.snapshot().split("\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].endsWith("adb: Shell opened"));
        assertTrue(lines[1].endsWith("target: [server] INFO: Device: Pixel"));
        // A partial shell line is flushed before the next step.
        assertTrue(lines[2].endsWith("target: Kill"));
        assertTrue(lines[3].endsWith("client: Session ended"));
        assertFalse(DiagnosticLog.isEmpty());
    }

    @Test
    public void includesStackTraces() {
        DiagnosticLog.e("adb", "Launch failed", new IllegalStateException("boom"));
        String text = DiagnosticLog.snapshot();
        assertTrue(text.contains("adb: Launch failed"));
        assertTrue(text.contains("java.lang.IllegalStateException: boom"));
    }

    @Test
    public void isBounded() {
        for (int i = 0; i < DiagnosticLog.MAX_LINES + 100; ++i) {
            DiagnosticLog.i("t", "line " + i);
        }
        String[] lines = DiagnosticLog.snapshot().split("\n");
        assertEquals(DiagnosticLog.MAX_LINES, lines.length);
        assertTrue(lines[lines.length - 1].endsWith("line " + (DiagnosticLog.MAX_LINES + 99)));
    }

    @Test
    public void diagnosticsCommandRunsServerSelfTestAndLogcat() {
        String command = AdbLauncher.buildDiagnosticsCommand();
        assertTrue(command.contains("com.genymobile.scrcpy.Server " + ScrcpySessionOptions.SERVER_VERSION));
        assertTrue(command.contains("list_displays=true"));
        assertTrue(command.contains("logcat -d"));
        // Must fit in one ADB OPEN packet (adblib advertises a 4 KiB maximum payload).
        assertTrue(command.length() + "shell:".length() < 4000);
    }
}
