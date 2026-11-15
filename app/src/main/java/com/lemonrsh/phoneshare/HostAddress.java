package com.lemonrsh.phoneshare;

/**
 * Parses the "host" or "host:port" text typed in the connection field, or a Cloudflare share
 * code such as {@code blue-river-cat.trycloudflare.com#123456} (see {@link CloudTunnel}).
 */
public final class HostAddress {
    public static final int DEFAULT_ADB_PORT = 5555;

    public final String host;
    public final int port;
    /** The share PIN of a Cloudflare share code, or null. */
    public final String pin;

    private HostAddress(String host, int port, String pin) {
        this.host = host;
        this.port = port;
        this.pin = pin;
    }

    public boolean isTunnel() {
        return CloudTunnel.isTunnelHost(host);
    }

    /**
     * Returns the parsed address, or {@code null} when the text is not a usable
     * host. Accepts {@code 192.168.1.4}, {@code 192.168.1.4:5556},
     * {@code [fe80::1]:5555} and bare IPv6 literals.
     */
    public static HostAddress parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        // The display form "<name>.trycloudflare.com (Cloudflare)" (see toString()).
        if (value.toLowerCase(java.util.Locale.ROOT).endsWith(DISPLAY_SUFFIX.toLowerCase(java.util.Locale.ROOT))) {
            value = value.substring(0, value.length() - DISPLAY_SUFFIX.length()).trim();
        }
        if (hasWhitespace(value)) {
            // A whole message was pasted ("Connect to my phone: https://…"): take the link.
            value = findTunnelToken(value);
            if (value == null) {
                return null;
            }
        }
        if (value.toLowerCase(java.util.Locale.ROOT).contains(CloudTunnel.SUFFIX)) {
            value = trimPunctuation(value);
        }
        if (value.isEmpty()) {
            return null;
        }
        String pin = null;
        int hash = value.indexOf('#');
        if (hash >= 0) {
            pin = value.substring(hash + 1);
            value = value.substring(0, hash);
            if (!pin.matches("[0-9]{4,12}")) {
                return null;
            }
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String scheme : new String[] {"https://", "http://", "wss://", "ws://"}) {
            if (lower.startsWith(scheme)) {
                value = value.substring(scheme.length());
                lower = lower.substring(scheme.length());
                break;
            }
        }
        // A share link may carry a path and a query added by messengers, e.g.
        // https://<name>.trycloudflare.com/482913?fbclid=… : the host ends at the first / or ?.
        int hostEnd = value.length();
        for (char stop : new char[] {'/', '?'}) {
            int index = value.indexOf(stop);
            if (index >= 0 && index < hostEnd) {
                hostEnd = index;
            }
        }
        if (CloudTunnel.isTunnelHost(value.substring(0, hostEnd))) {
            String host = value.substring(0, hostEnd).toLowerCase(java.util.Locale.ROOT);
            if (!host.matches("[a-z0-9.-]+")) {
                return null;
            }
            if (pin == null) {
                // Link form https://<name>.trycloudflare.com/<PIN>
                String path = value.substring(hostEnd);
                int question = path.indexOf('?');
                if (question >= 0) {
                    path = path.substring(0, question);
                }
                path = path.replaceAll("^/+|/+$", "");
                if (path.matches("[0-9]{4,12}")) {
                    pin = path;
                }
            }
            CloudTunnel.rememberPin(host, pin);
            return new HostAddress(host, DEFAULT_ADB_PORT, pin);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (pin != null || value.isEmpty()) {
            return null;
        }

        String host = value;
        int port = DEFAULT_ADB_PORT;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end <= 1) {
                return null;
            }
            host = value.substring(1, end);
            String rest = value.substring(end + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return null;
                }
                port = parsePort(rest.substring(1));
            }
        } else {
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                host = value.substring(0, firstColon);
                port = parsePort(value.substring(firstColon + 1));
            }
            // More than one colon without brackets: a bare IPv6 literal.
        }

        if (host.isEmpty() || port <= 0) {
            return null;
        }
        return new HostAddress(host, port, null);
    }

    private static final String DISPLAY_SUFFIX = " (Cloudflare)";

    private static boolean hasWhitespace(String value) {
        for (int i = 0; i < value.length(); ++i) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** The first word containing ".trycloudflare.com", or null. */
    private static String findTunnelToken(String text) {
        for (String token : text.split("\\s+")) {
            if (token.toLowerCase(java.util.Locale.ROOT).contains(CloudTunnel.SUFFIX)) {
                return token;
            }
        }
        return null;
    }

    /** Removes quotes, brackets and sentence punctuation around a pasted link. */
    private static String trimPunctuation(String value) {
        String wrap = ".,;:!?'\"()[]<>{}";
        int start = 0;
        int end = value.length();
        while (start < end && wrap.indexOf(value.charAt(start)) >= 0) {
            ++start;
        }
        while (end > start && wrap.indexOf(value.charAt(end - 1)) >= 0) {
            --end;
        }
        return value.substring(start, end);
    }

    /**
     * Text that {@link #parse} turns back into this address, PIN included (unlike
     * {@link #toString()}, which is for display). Used to hand the address to the floating window.
     */
    public String toConnectString() {
        if (isTunnel()) {
            return pin == null ? host : host + "#" + pin;
        }
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    private static int parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) {
            return -1;
        }
        for (int i = 0; i < text.length(); ++i) {
            if (!Character.isDigit(text.charAt(i))) {
                return -1;
            }
        }
        int port = Integer.parseInt(text);
        return port >= 1 && port <= 65535 ? port : -1;
    }

    @Override
    public String toString() {
        if (isTunnel()) {
            return host + DISPLAY_SUFFIX;
        }
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
