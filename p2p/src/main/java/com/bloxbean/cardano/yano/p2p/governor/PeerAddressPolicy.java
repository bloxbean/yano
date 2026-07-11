package com.bloxbean.cardano.yano.p2p.governor;

import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import com.bloxbean.cardano.yano.p2p.connection.ConnectionKey;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Source-aware address hygiene for configured and discovered upstream peers.
 */
public final class PeerAddressPolicy {
    private final boolean allowPrivateAddresses;
    private final Set<String> allowlist;
    private final Set<String> denylist;

    public PeerAddressPolicy(UpstreamDiscoveryConfig config) {
        this.allowPrivateAddresses = config != null && config.isAllowPrivateAddresses();
        this.allowlist = normalize(config != null ? config.getAllowlist() : List.of());
        this.denylist = normalize(config != null ? config.getDenylist() : List.of());
    }

    public boolean allows(String host, int port) {
        return allows(PeerSource.GOSSIP, host, port);
    }

    public boolean allows(PeerSource source, String host, int port) {
        if (host == null || host.isBlank() || port <= 0 || port > 65_535) {
            return false;
        }
        PeerSource effectiveSource = source != null ? source : PeerSource.GOSSIP;
        String normalizedHost = normalizeHost(host);
        String endpoint = endpointKey(normalizedHost, port);
        if (denylist.contains(normalizedHost) || denylist.contains(endpoint)) {
            return false;
        }
        if (!allowlist.isEmpty()
                && !allowlist.contains(normalizedHost)
                && !allowlist.contains(endpoint)) {
            return false;
        }
        if (allowPrivateAddresses || effectiveSource == PeerSource.STATIC_UPSTREAM
                || effectiveSource == PeerSource.LOCAL_ROOT) {
            return true;
        }
        return !isPrivateLiteralOrLocalhost(normalizedHost);
    }

    public static Optional<HostPort> parseEndpoint(String raw, int defaultPort) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close <= 1) {
                return Optional.empty();
            }
            String host = value.substring(1, close).trim();
            if (close == value.length() - 1) {
                return defaultPort > 0 ? Optional.of(new HostPort(host, defaultPort)) : Optional.empty();
            }
            if (value.charAt(close + 1) != ':') {
                return Optional.empty();
            }
            return parsePort(value.substring(close + 2)).map(port -> new HostPort(host, port));
        }

        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon != lastColon) {
            return defaultPort > 0 ? Optional.of(new HostPort(value, defaultPort)) : Optional.empty();
        }
        if (lastColon <= 0 || lastColon == value.length() - 1) {
            return defaultPort > 0 ? Optional.of(new HostPort(value, defaultPort)) : Optional.empty();
        }
        String host = value.substring(0, lastColon).trim();
        return parsePort(value.substring(lastColon + 1)).map(port -> new HostPort(host, port));
    }

    public record HostPort(String host, int port) {
        public HostPort {
            Objects.requireNonNull(host, "host");
        }
    }

    private boolean isPrivateLiteralOrLocalhost(String host) {
        if ("localhost".equals(host)) {
            return true;
        }
        if (!looksLikeIpLiteral(host)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(stripIpv6Brackets(host));
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || isUniqueLocalIpv6(address)
                    || address.isMulticastAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static Optional<Integer> parsePort(String portRaw) {
        try {
            int port = Integer.parseInt(portRaw.trim());
            return port > 0 && port <= 65_535 ? Optional.of(port) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Set<String> normalize(List<String> values) {
        Set<String> normalized = new HashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                PeerAddressPolicy.parseEndpoint(value, -1)
                        .map(hostPort -> endpointKey(normalizeHost(hostPort.host()), hostPort.port()))
                        .ifPresentOrElse(normalized::add, () -> normalized.add(normalizeHost(value)));
            }
        }
        return normalized;
    }

    private static String normalizeHost(String host) {
        return ConnectionKey.of(stripIpv6Brackets(host.trim()), 1).host();
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

    private static String endpointKey(String host, int port) {
        return ConnectionKey.of(host, port).displayName();
    }
}
