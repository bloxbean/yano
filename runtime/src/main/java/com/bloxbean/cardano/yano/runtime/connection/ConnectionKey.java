package com.bloxbean.cardano.yano.runtime.connection;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Host/port identity for a TCP connection. Protocol magic is intentionally not
 * part of this key.
 */
public record ConnectionKey(String host, int port) {
    public ConnectionKey {
        host = normalizeHost(host);
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    public static ConnectionKey of(String host, int port) {
        return new ConnectionKey(host, port);
    }

    public static Optional<ConnectionKey> from(SocketAddress address) {
        if (address instanceof InetSocketAddress inetSocketAddress) {
            InetAddress inetAddress = inetSocketAddress.getAddress();
            String host = inetAddress != null
                    ? inetAddress.getHostAddress()
                    : inetSocketAddress.getHostString();
            return Optional.of(new ConnectionKey(host, inetSocketAddress.getPort()));
        }
        return Optional.empty();
    }

    public String displayName() {
        return host + ":" + port;
    }

    public String ipKey() {
        return host;
    }

    private static String normalizeHost(String value) {
        Objects.requireNonNull(value, "host");
        String host = stripIpv6Brackets(value.trim());
        if (host.isBlank()) {
            return host;
        }
        if (looksLikeIpLiteral(host)) {
            try {
                return InetAddress.getByName(host).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException ignored) {
                return host.toLowerCase(Locale.ROOT);
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static String stripIpv6Brackets(String value) {
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean looksLikeIpLiteral(String value) {
        return value.indexOf(':') >= 0 || value.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }
}
