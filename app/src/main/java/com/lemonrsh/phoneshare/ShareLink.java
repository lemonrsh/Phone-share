package com.lemonrsh.phoneshare;

import java.util.Locale;

/**
 * The share link of "Share this phone over the internet": {@code https://<name>.trycloudflare.com/<PIN>}.
 * (Links of r22/Beta 1, {@code https://<name>.trycloudflare.com/#<PIN>}, still work. The PIN moved
 * into the path because some messengers and in-app browsers drop the {@code #} part.)
 * Opening it in a browser shows a small page served by the shared phone itself
 * ({@link WsBridgeServer}) that opens Phone Share with a {@code phoneshare://connect?host=…&pin=…}
 * link, so the other person only has to tap Connect.
 */
final class ShareLink {
    static final String APP_PACKAGE = "com.lemonrsh.phoneshare";
    static final String SCHEME = "phoneshare";
    // Connecting through a share link needs the cloudflared version (listed on the releases page).
    static final String DOWNLOAD_URL = "https://github.com/lemonrsh/androidscrcpy/releases";

    private ShareLink() {
    }

    /** The link sent to the other phone, or null while the tunnel is not ready. */
    static String build(String host, String pin) {
        if (host == null || pin == null) {
            return null;
        }
        return "https://" + host + "/" + pin;
    }

    /**
     * Returns the share code {@code host#PIN} contained in a share link
     * ({@code https://host/#PIN}), an app link ({@code phoneshare://connect?host=…&pin=…}) or a
     * pasted code, or null when it is not a valid Cloudflare share.
     */
    static String codeFrom(String link) {
        if (link == null) {
            return null;
        }
        String text = link.trim();
        String candidate = text;
        if (text.toLowerCase(Locale.ROOT).startsWith(SCHEME + "://")) {
            String rest = text.substring(SCHEME.length() + 3);
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                rest = rest.substring(0, hash);
            }
            int question = rest.indexOf('?');
            if (question < 0) {
                return null;
            }
            String query = rest.substring(question + 1);
            String host = WsBridgeServer.queryParam(query, "host");
            String pin = WsBridgeServer.queryParam(query, "pin");
            if (host == null || pin == null) {
                return null;
            }
            candidate = host + "#" + pin;
        }
        HostAddress address = HostAddress.parse(candidate);
        if (address == null || !address.isTunnel() || address.pin == null) {
            return null;
        }
        return address.host + "#" + address.pin;
    }

    /** The page shown when the link is opened in a browser. Static: the page reads its own URL. */
    static String landingPage() {
        return PAGE.replace("__PKG__", APP_PACKAGE).replace("__SCHEME__", SCHEME).replace("__DL__", DOWNLOAD_URL);
    }

    private static final String PAGE = "<!doctype html>\n"
            + "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<meta name=\"robots\" content=\"noindex\">\n"
            + "<title>Connect with Phone Share</title>\n"
            + "<style>\n"
            + "body{margin:0;font-family:system-ui,Roboto,sans-serif;background:#157a3c;color:#10351f;"
            + "display:flex;min-height:100vh;align-items:center;justify-content:center}\n"
            + ".card{background:#effcf3;border-radius:24px;padding:28px 24px;margin:16px;max-width:420px;"
            + "width:100%;box-sizing:border-box;text-align:center;box-shadow:0 8px 24px rgba(0,0,0,.25)}\n"
            + "h1{font-size:22px;margin:8px 0 12px}p{line-height:1.45;margin:10px 0}\n"
            + ".icon{font-size:44px}.btn{display:block;padding:15px;border-radius:999px;margin:14px 0 0;"
            + "font-weight:600;font-size:17px;text-decoration:none}\n"
            + ".open{background:#14532d;color:#fff}.dl{background:#d6f5df;color:#14532d}\n"
            + ".small{font-size:13px;color:#3d6b4e}code{display:block;word-break:break-all;background:#d6f5df;"
            + "padding:10px;border-radius:12px;font-size:13px;user-select:all}\n"
            + "</style></head><body><div class=\"card\">\n"
            + "<div class=\"icon\">&#128241;</div>\n"
            + "<h1>Connect with Phone Share</h1>\n"
            + "<p id=\"msg\">Opening Phone Share&hellip;</p>\n"
            + "<a id=\"open\" class=\"btn open\" href=\"#\">Open in Phone Share</a>\n"
            + "<a class=\"btn dl\" href=\"__DL__\">Download Phone Share</a>\n"
            + "<p class=\"small\">Get the newest release and install the file ending in <b>-cloudflared-debug.apk</b>.</p>\n"
            + "<p class=\"small\">Or copy this link, paste it into the address box of Phone Share and tap Connect:</p>\n"
            + "<code id=\"code\"></code>\n"
            + "</div><script>\n"
            + "(function(){\n"
            + "var pin=location.pathname.replace(/^\\/+|\\/+$/g,'');\n"
            + "if(!/^[0-9]{4,12}$/.test(pin)){pin=(location.hash||'').replace('#','');}\n"
            + "var host=location.hostname;\n"
            + "var msg=document.getElementById('msg'),open=document.getElementById('open');\n"
            + "document.getElementById('code').textContent='https://'+host+'/'+pin;\n"
            + "if(!/^[0-9]{4,12}$/.test(pin)){msg.textContent='This link is missing its PIN. Ask for the full link again.';"
            + "open.style.display='none';return;}\n"
            + "var q='connect?host='+encodeURIComponent(host)+'&pin='+pin;\n"
            + "var android=/Android/i.test(navigator.userAgent);\n"
            + "var back=location.origin+'/'+pin+'?noapp=1';\n"
            + "function intent(fallback){return 'intent://'+q+'#Intent;scheme=__SCHEME__;package=__PKG__;"
            + "S.browser_fallback_url='+encodeURIComponent(fallback)+';end';}\n"
            + "open.href=android?intent('__DL__'):'__SCHEME__://'+q;\n"
            + "if(location.search.indexOf('noapp=1')>=0){msg.textContent='Phone Share is not installed on this phone. "
            + "Download it, install it, then open the link again.';return;}\n"
            + "if(android){setTimeout(function(){location.href=intent(back);},200);}\n"
            + "setTimeout(function(){msg.textContent='If Phone Share did not open, tap the button. "
            + "No app yet? Download it first.';},2500);\n"
            + "})();\n"
            + "</script></body></html>\n";
}
