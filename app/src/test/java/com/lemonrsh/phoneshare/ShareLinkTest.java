package com.lemonrsh.phoneshare;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ShareLinkTest {
    @Test
    public void buildsLinkOnly() {
        assertEquals("https://blue-river-cat.trycloudflare.com/#123456",
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
    public void rejectsBadLinks() {
        assertNull(ShareLink.codeFrom(null));
        assertNull(ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/"));
        assertNull(ShareLink.codeFrom("https://blue-river-cat.trycloudflare.com/#12ab"));
        assertNull(ShareLink.codeFrom("https://example.com/#123456"));
        assertNull(ShareLink.codeFrom("phoneshare://connect?host=example.com&pin=123456"));
        assertNull(ShareLink.codeFrom("phoneshare://connect?host=blue-river-cat.trycloudflare.com"));
        assertNull(ShareLink.codeFrom("phoneshare://connect"));
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
