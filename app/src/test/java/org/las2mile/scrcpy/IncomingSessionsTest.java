package org.las2mile.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class IncomingSessionsTest {
    @Test
    public void parsesBeaconStatus() {
        IncomingSessions.Session session = IncomingSessions.parse(27200, "SCRCPY\t100.93.68.10\tGalaxy S24\t1700000000000");
        assertEquals(27200, session.port);
        assertEquals("100.93.68.10", session.clientIp);
        assertEquals("Galaxy S24", session.clientName);
        assertEquals(1700000000000L, session.startMillis);
    }

    @Test
    public void ignoresOtherServices() {
        assertNull(IncomingSessions.parse(27200, null));
        assertNull(IncomingSessions.parse(27200, "HTTP/1.1 400 Bad Request"));
        assertNull(IncomingSessions.parse(27200, "SCRCPY\tonly-two"));
    }

    @Test
    public void portsMatchTheServer() {
        // Must match com.genymobile.scrcpy.device.SessionBeacon in the server module.
        assertEquals(27200, IncomingSessions.FIRST_PORT);
        assertEquals(5, IncomingSessions.PORT_COUNT);
    }
}
