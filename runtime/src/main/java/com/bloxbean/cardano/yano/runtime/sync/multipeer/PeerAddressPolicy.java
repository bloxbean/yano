package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Address hygiene for configured and discovered upstream peers.
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
        if (host == null || host.isBlank() || port <= 0 || port > 65_535) {
            return false;
        }
        String normalizedHost = normalizeHost(host);
        String endpoint = normalizedHost + ":" + port;
        if (denylist.contains(normalizedHost) || denylist.contains(endpoint)) {
            return false;
        }
        if (!allowlist.isEmpty()
                && !allowlist.contains(normalizedHost)
                && !allowlist.contains(endpoint)) {
            return false;
        }
        return allowPrivateAddresses || !isPrivateAddress(normalizedHost);
    }

    public static Optional<HostPort> parseEndpoint(String raw, int defaultPort) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        int split = value.lastIndexOf(':');
        if (split <= 0 || split == value.length() - 1) {
            return defaultPort > 0 ? Optional.of(new HostPort(value, defaultPort)) : Optional.empty();
        }
        String host = value.substring(0, split).trim();
        String portRaw = value.substring(split + 1).trim();
        try {
            int port = Integer.parseInt(portRaw);
            return Optional.of(new HostPort(host, port));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public record HostPort(String host, int port) {
        public HostPort {
            Objects.requireNonNull(host, "host");
        }
    }

    private boolean isPrivateAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static Set<String> normalize(List<String> values) {
        Set<String> normalized = new HashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(normalizeHost(value));
            }
        }
        return normalized;
    }

    private static String normalizeHost(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }
}
