package com.lemonrsh.phoneshare;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ShareLinkTest {
    @Test
    public void buildsLinkOnly() {
        assertEquals("https://blue-river-cat.trycloudflare.com/123456",
                ShareLink.build("blue-river-cat.trycloudflare.com", "123456"));
        assertNull(ShareLink.build(null, "123456"));
        assertNull(ShareLink.build("blue-river-cat.trycloudflare.com", null));
    }

    @Test
    public void linkCanBePastedIntoTheAddressBox() {
        HostAddress address = HostAddress.parse("https://blue-river-cat.trycloudflare.com/#123456");
        assertTrue(address.isTunnel());
        assertEquals("blue-river-cat.trycloudflare.com", address.host);
        assertEquals("123456", address.pin);
    }

    @Test
    public void readsCodeFromLinks() {
        assertEquals("blue-river-cat.trycloudflare.com#123456",
                ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/#123456"));
        assertEquals("blue-river-cat.trycloudflare.com#123456",
                ShareLink.codeFrom("phoneshare://connect?host=blue-river-cat.trycloudflare.com&pin=123456"));
        assertEquals("blue-river-cat.trycloudflare.com#654321",
                ShareLink.codeFrom("phoneshare://connect?pin=654321&host=Blue-River-Cat.trycloudflare.com"));
        assertEquals("blue-river-cat.trycloudflare.com#123456",
                ShareLink.codeFrom("blue-river-cat.trycloudflare.com#123456"));
    }

    @Test
    public void acceptsLinksMangledByMessengers() {
        String code = "blue-river-cat.trycloudflare.com#123456";
        // New path form, with and without a trailing slash or tracking query.
        assertEquals(code, ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/123456"));
        assertEquals(code, ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/123456/"));
        assertEquals(code, ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/123456?fbclid=IwAR0abc"));
        // Old fragment form with a query added before the fragment (Facebook / Messenger).
        assertEquals(code, ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/?fbclid=IwAR0abc#123456"));
        // Whole message pasted, link in brackets or followed by punctuation.
        assertEquals(code, ShareLink.codeFrom("Connect to my phone: https://blue-river-cat.trycloudflare.com/123456."));
        assertEquals(code, ShareLink.codeFrom("(https://blue-river-cat.trycloudflare.com/#123456)"));
        assertEquals(code, ShareLink.codeFrom("Paste this code: blue-river-cat.trycloudflare.com#123456\nThanks"));
    }

    @Test
    public void floatingWindowAddressRoundTrips() {
        HostAddress address = HostAddress.parse("https://blue-river-cat.trycloudflare.com/123456");
        // The floating window used to receive toString() ("… (Cloudflare)") and said "Invalid target address".
        HostAddress again = HostAddress.parse(address.toConnectString());
        assertEquals("blue-river-cat.trycloudflare.com", again.host);
        assertEquals("123456", again.pin);
        HostAddress display = HostAddress.parse(address.toString());
        assertEquals("blue-river-cat.trycloudflare.com", display.host);
        HostAddress ip = HostAddress.parse(HostAddress.parse("192.168.1.4:5556").toConnectString());
        assertEquals("192.168.1.4", ip.host);
        assertEquals(5556, ip.port);
        HostAddress v6 = HostAddress.parse(HostAddress.parse("fe80::1").toConnectString());
        assertEquals("fe80::1", v6.host);
        assertEquals(5555, v6.port);
    }

    @Test
    public void rejectsBadLinks() {
        assertNull(ShareLink.codeFrom(null));
        assertNull(ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/"));
        assertNull(ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/#12ab"));
        assertNull(ShareLink.codeFrom("https://example.com/#123456"));
        assertNull(ShareLink.codeFrom("phoneshare://connect?host=example.com&pin=123456"));
        assertNull(ShareLink.codeFrom("phoneshare://connect?host=blue-river-cat.trycloudflare.com"));
        assertNull(ShareLink.codeFrom("phoneshare://connect"));
        assertNull(ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/12ab"));
        assertNull(ShareLink.codeFrom("hello there"));
    }

    @Test
    public void landingPageOpensTheApp() {
        String page = ShareLink.landingPage();
        assertTrue(page.contains("scheme=phoneshare;package=com.lemonrsh.phoneshare"));
        assertTrue(page.contains("'phoneshare://'+q"));
        assertTrue(page.contains(ShareLink.DOWNLOAD_URL));
        assertFalse(page.contains("__PKG__"));
        assertFalse(page.contains("__SCHEME__"));
        assertFalse(page.contains("__DL__"));
    }
}
