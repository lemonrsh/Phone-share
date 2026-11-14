package org.las2mile.scrcpy;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ShellOutputTest {
    private static final Pattern HASH = Pattern.compile("SCRCPY_HASH_([0-9a-fA-F]*)_END");

    @Test
    public void echoedCommandDoesNotMatchMarker() throws Exception {
        ShellOutput output = new ShellOutput();
        // The tty echoes the command, then the shell prints the result in pieces.
        output.append("h=$(md5sum /data/local/tmp/scrcpy-server.jar 2>/dev/null); echo SCRCPY_HASH_${h%% *}_END\r\n");
        output.append("SCRCPY_HASH_0123abcd");
        output.append("ef_END\r\n:/ $ ");
        assertEquals("0123abcdef", output.await(HASH, 1000));
    }

    @Test
    public void missingHashIsEmpty() throws Exception {
        ShellOutput output = new ShellOutput();
        output.append("SCRCPY_HASH__END\r\n");
        assertEquals("", output.await(HASH, 1000));
    }

    @Test
    public void chunkMarkersAreUnambiguous() throws Exception {
        ShellOutput output = new ShellOutput();
        output.append("echo AAAA >> f; echo SCRCPY_C\"\"_1_\r\nSCRCPY_C_1_\r\n");
        output.append("SCRCPY_C_10_\r\n");
        assertEquals("SCRCPY_C_1_", output.await(Pattern.compile("SCRCPY_C_1_"), 1000));
        assertEquals("SCRCPY_C_10_", output.await(Pattern.compile("SCRCPY_C_10_"), 1000));
    }

    @Test
    public void timesOutAndReportsClose() throws Exception {
        ShellOutput output = new ShellOutput();
        assertNull(output.await(Pattern.compile("NEVER"), 50));
        output.append("[server] ERROR: Could not open video stream\n");
        output.close();
        assertTrue(output.isClosed());
        assertNull(output.await(Pattern.compile("NEVER"), 1000));
        assertEquals("[server] ERROR: Could not open video stream", output.lastErrorLine());
    }

    @Test
    public void wakesWaitingThread() throws Exception {
        final ShellOutput output = new ShellOutput();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // Test thread.
            }
            output.append("SCRCPY_READY\n");
        });
        writer.start();
        assertEquals("SCRCPY_READY", output.await(Pattern.compile("SCRCPY_READY"), 5000));
        writer.join();
    }

    @Test
    public void lastServerLinesSkipLauncherMarkersAndPrompt() {
        ShellOutput output = new ShellOutput();
        output.append("SCRCPY_HASH_abc_END\r\n:/ $ ");
        assertNull(output.lastServerLines(3));
        output.append("[server] INFO: Device: Pixel\r\n");
        output.append("Killed\r\n");
        output.close();
        assertNull(output.lastErrorLine());
        assertEquals("[server] INFO: Device: Pixel / Killed", output.lastServerLines(3));
        assertEquals("Killed", output.lastServerLines(1));
    }

    @Test
    public void errorLineAfterPromptIsFound() {
        ShellOutput output = new ShellOutput();
        output.append("SCRCPY_HASH_abc_END\r\n:/ $ [server] ERROR: Could not open video stream\r\n");
        output.close();
        assertEquals("[server] ERROR: Could not open video stream", output.lastErrorLine());
    }
}
