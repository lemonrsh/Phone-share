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
        if (value.isEmpty() || value.indexOf(' ') >= 0) {
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
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (CloudTunnel.isTunnelHost(value)) {
            String host = value.toLowerCase(java.util.Locale.ROOT);
            if (!host.matches("[a-z0-9.-]+")) {
                return null;
            }
            CloudTunnel.rememberPin(host, pin);
            return new HostAddress(host, DEFAULT_ADB_PORT, pin);
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
            return host + " (Cloudflare)";
        }
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
