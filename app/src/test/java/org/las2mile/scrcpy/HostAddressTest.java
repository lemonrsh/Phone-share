package org.las2mile.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HostAddressTest {
    @Test
    public void usesDefaultAdbPort() {
        HostAddress address = HostAddress.parse(" 192.168.1.42 ");
        assertEquals("192.168.1.42", address.host);
        assertEquals(5555, address.port);
    }

    @Test
    public void parsesExplicitPort() {
        HostAddress address = HostAddress.parse("192.168.1.42:5556");
        assertEquals("192.168.1.42", address.host);
        assertEquals(5556, address.port);
        assertEquals("192.168.1.42:5556", address.toString());
    }

    @Test
    public void parsesIpv6() {
        HostAddress bracketed = HostAddress.parse("[fe80::1]:6000");
        assertEquals("fe80::1", bracketed.host);
        assertEquals(6000, bracketed.port);
        HostAddress bare = HostAddress.parse("fe80::1");
        assertEquals("fe80::1", bare.host);
        assertEquals(5555, bare.port);
        assertEquals("[fe80::1]:5555", bare.toString());
    }

    @Test
    public void rejectsInvalidInput() {
        assertNull(HostAddress.parse(null));
        assertNull(HostAddress.parse(""));
        assertNull(HostAddress.parse("192.168.1.42:"));
        assertNull(HostAddress.parse("192.168.1.42:0"));
        assertNull(HostAddress.parse("192.168.1.42:70000"));
        assertNull(HostAddress.parse("192.168.1.42:abc"));
        assertNull(HostAddress.parse("192.168 .1.42"));
        assertNull(HostAddress.parse("[fe80::1"));
    }
}
