package org.las2mile.scrcpy;

/** Parses the "host" or "host:port" text typed in the connection field. */
public final class HostAddress {
    public static final int DEFAULT_ADB_PORT = 5555;

    public final String host;
    public final int port;

    private HostAddress(String host, int port) {
        this.host = host;
        this.port = port;
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
        return new HostAddress(host, port);
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
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
