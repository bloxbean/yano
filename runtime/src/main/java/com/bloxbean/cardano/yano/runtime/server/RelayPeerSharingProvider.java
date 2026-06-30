package com.bloxbean.cardano.yano.runtime.server;

import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.PeerAddress;
import com.bloxbean.cardano.yano.runtime.connection.RelayConnectionInfo;
import com.bloxbean.cardano.yano.runtime.sync.multipeer.PeerAddressPolicy;
import com.bloxbean.cardano.yano.runtime.sync.multipeer.PeerStoreEntry;
import org.slf4j.Logger;

import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Builds bounded peer-sharing responses from Yano's advertised endpoint and
 * current upstream peer store.
 */
final class RelayPeerSharingProvider {
    private final boolean enabled;
    private final String advertisedHost;
    private final int advertisedPort;
    private final PeerAddressPolicy addressPolicy;
    private final Supplier<List<PeerStoreEntry>> peerStoreSupplier;
    private final Supplier<List<RelayConnectionInfo>> connectionSupplier;
    private final Supplier<List<String>> selfHostSupplier;
    private final Logger log;
    private final AtomicInteger rotation = new AtomicInteger();

    RelayPeerSharingProvider(boolean enabled,
                             String advertisedHost,
                             int advertisedPort,
                             boolean allowPrivateAddresses,
                             Supplier<List<PeerStoreEntry>> peerStoreSupplier,
                             Logger log) {
        this(enabled, advertisedHost, advertisedPort, allowPrivateAddresses, peerStoreSupplier, List::of,
                RelayPeerSharingProvider::discoverLocalInterfaceHosts, log);
    }

    RelayPeerSharingProvider(boolean enabled,
                             String advertisedHost,
                             int advertisedPort,
                             boolean allowPrivateAddresses,
                             Supplier<List<PeerStoreEntry>> peerStoreSupplier,
                             Supplier<List<RelayConnectionInfo>> connectionSupplier,
                             boolean includeConnectionPeers,
                             Logger log) {
        this(enabled, advertisedHost, advertisedPort, allowPrivateAddresses, peerStoreSupplier, connectionSupplier,
                RelayPeerSharingProvider::discoverLocalInterfaceHosts, log);
    }

    RelayPeerSharingProvider(boolean enabled,
                             String advertisedHost,
                             int advertisedPort,
                             boolean allowPrivateAddresses,
                             Supplier<List<PeerStoreEntry>> peerStoreSupplier,
                             Supplier<List<String>> selfHostSupplier,
                             Logger log) {
        this(enabled, advertisedHost, advertisedPort, allowPrivateAddresses, peerStoreSupplier, List::of,
                selfHostSupplier, log);
    }

    RelayPeerSharingProvider(boolean enabled,
                             String advertisedHost,
                             int advertisedPort,
                             boolean allowPrivateAddresses,
                             Supplier<List<PeerStoreEntry>> peerStoreSupplier,
                             Supplier<List<RelayConnectionInfo>> connectionSupplier,
                             Supplier<List<String>> selfHostSupplier,
                             Logger log) {
        this.enabled = enabled;
        this.advertisedHost = advertisedHost != null ? advertisedHost.trim() : "";
        this.advertisedPort = advertisedPort;
        var policyConfig = com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig.builder()
                .allowPrivateAddresses(allowPrivateAddresses)
                .build();
        this.addressPolicy = new PeerAddressPolicy(policyConfig);
        this.peerStoreSupplier = peerStoreSupplier != null ? peerStoreSupplier : List::of;
        this.connectionSupplier = connectionSupplier != null ? connectionSupplier : List::of;
        this.selfHostSupplier = selfHostSupplier != null ? selfHostSupplier : List::of;
        this.log = Objects.requireNonNull(log, "log");
    }

    List<PeerAddress> peers(int requestedAmount) {
        if (!enabled || requestedAmount <= 0) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<PeerAddress> candidates = new ArrayList<>();
        addSelf(candidates, seen);
        addEstablishedOutboundPeers(candidates, seen, requestedAmount);

        List<PeerStoreEntry> storeEntries = peerStoreSupplier.get();
        if (storeEntries != null && !storeEntries.isEmpty()) {
            int start = Math.floorMod(rotation.getAndIncrement(), storeEntries.size());
            for (int i = 0; i < storeEntries.size(); i++) {
                PeerStoreEntry entry = storeEntries.get((start + i) % storeEntries.size());
                if (entry != null) {
                    add(candidates, seen, entry.host(), entry.port(), entry.source());
                }
                if (candidates.size() >= requestedAmount) {
                    break;
                }
            }
        }

        if (candidates.size() <= requestedAmount) {
            return List.copyOf(candidates);
        }
        return List.copyOf(candidates.subList(0, requestedAmount));
    }

    private void addEstablishedOutboundPeers(List<PeerAddress> candidates, Set<String> seen, int requestedAmount) {
        List<RelayConnectionInfo> connections = connectionSupplier.get();
        if (connections == null || connections.isEmpty()) {
            return;
        }
        for (RelayConnectionInfo connection : connections) {
            if (connection == null || !connection.outbound() || !connection.usableForPeerSharing()) {
                continue;
            }
            add(candidates, seen, connection.key().host(), connection.key().port(), "connection");
            if (candidates.size() >= requestedAmount) {
                return;
            }
        }
    }

    private void addSelf(List<PeerAddress> candidates, Set<String> seen) {
        for (String host : selfHosts()) {
            int before = candidates.size();
            add(candidates, seen, host, advertisedPort, "self");
            if (candidates.size() > before) {
                return;
            }
        }
    }

    private List<String> selfHosts() {
        if (!advertisedHost.isBlank() && !"auto".equalsIgnoreCase(advertisedHost)) {
            return List.of(advertisedHost);
        }
        List<String> hosts = selfHostSupplier.get();
        return hosts != null ? hosts : List.of();
    }

    private void add(List<PeerAddress> candidates,
                     Set<String> seen,
                     String host,
                     int port,
                     String source) {
        if (!addressPolicy.allows(host, port)) {
            return;
        }
        String key = host.trim().toLowerCase(java.util.Locale.ROOT) + ":" + port;
        if (!seen.add(key)) {
            return;
        }
        toPeerAddress(host, port, source).ifPresent(candidates::add);
    }

    private java.util.Optional<PeerAddress> toPeerAddress(String host, int port, String source) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address instanceof Inet4Address) {
                return java.util.Optional.of(PeerAddress.ipv4(address.getHostAddress(), port));
            }
            if (address instanceof Inet6Address) {
                return java.util.Optional.of(PeerAddress.ipv6(address.getHostAddress(), port));
            }
        } catch (Exception e) {
            log.debug("Skipping peer-sharing {} peer {}:{}: {}", source, host, port, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    private static List<String> discoverLocalInterfaceHosts() {
        try {
            List<String> publicHosts = new ArrayList<>();
            List<String> privateHosts = new ArrayList<>();
            List<String> loopbackHosts = new ArrayList<>();
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return List.of();
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address.isAnyLocalAddress()
                            || address.isLinkLocalAddress()
                            || address.isMulticastAddress()) {
                        continue;
                    }
                    String host = address.getHostAddress();
                    if (host == null || host.isBlank()) {
                        continue;
                    }
                    if (address.isLoopbackAddress()) {
                        loopbackHosts.add(host);
                    } else if (address.isSiteLocalAddress()) {
                        privateHosts.add(host);
                    } else {
                        publicHosts.add(host);
                    }
                }
            }
            List<String> result = new ArrayList<>();
            result.addAll(publicHosts);
            result.addAll(privateHosts);
            result.addAll(loopbackHosts);
            return result.stream()
                    .map(host -> host.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
