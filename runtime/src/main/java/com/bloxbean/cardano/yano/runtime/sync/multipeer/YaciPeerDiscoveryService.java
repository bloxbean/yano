package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yaci.core.network.NodeClientConfig;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.PeerAddress;
import com.bloxbean.cardano.yaci.helper.PeerDiscovery;
import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import com.bloxbean.cardano.yano.runtime.peer.DefaultPeerClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bridges Yaci peer-sharing discovery into Yano's peer store.
 */
public final class YaciPeerDiscoveryService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(YaciPeerDiscoveryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;

    private final long protocolMagic;
    private final UpstreamDiscoveryConfig config;
    private final PeerAddressPolicy addressPolicy;
    private final Consumer<PeerStoreEntry> discoveredPeerConsumer;
    private final List<PeerDiscovery> runningDiscoveries = new CopyOnWriteArrayList<>();
    private final NodeClientConfig nodeClientConfig;
    private final HttpClient httpClient;
    private final AtomicBoolean peerSnapshotsLoaded = new AtomicBoolean(false);

    public YaciPeerDiscoveryService(long protocolMagic,
                                    UpstreamDiscoveryConfig config,
                                    PeerAddressPolicy addressPolicy,
                                    Consumer<PeerStoreEntry> discoveredPeerConsumer) {
        this.protocolMagic = protocolMagic;
        this.config = config;
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.discoveredPeerConsumer = Objects.requireNonNull(discoveredPeerConsumer, "discoveredPeerConsumer");
        this.nodeClientConfig = DefaultPeerClientFactory.supervisedNodeClientConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void start() {
        if (config == null || !config.isEnabled()) {
            return;
        }
        loadTopologyOnce();
        loadPeerSnapshotsOnce();
        if (config.isLedgerPeers()) {
            log.warn("Ledger peer discovery is enabled but no rollback-safe ledger peer provider is available yet");
        }
        if (!config.isPeerSharing()) {
            return;
        }
        if (!runningDiscoveries.isEmpty()) {
            return;
        }
        List<String> seeds = config.getSeeds() != null ? config.getSeeds() : List.of();
        for (String seed : seeds) {
            PeerAddressPolicy.parseEndpoint(seed, -1)
                    .filter(seedEndpoint -> addressPolicy.allows(PeerSource.BOOTSTRAP,
                            seedEndpoint.host(), seedEndpoint.port()))
                    .ifPresent(seedEndpoint -> startSeedDiscovery(seedEndpoint.host(), seedEndpoint.port()));
        }
    }

    public boolean isRunning() {
        return runningDiscoveries.stream().anyMatch(PeerDiscovery::isRunning);
    }

    @Override
    public void close() {
        for (PeerDiscovery discovery : new ArrayList<>(runningDiscoveries)) {
            try {
                discovery.shutdown();
            } catch (Exception e) {
                log.debug("Error shutting down peer discovery {}:{}", discovery.getHost(), discovery.getPort(), e);
            }
        }
        runningDiscoveries.clear();
        peerSnapshotsLoaded.set(false);
    }

    private void loadPeerSnapshotsOnce() {
        if (!peerSnapshotsLoaded.compareAndSet(false, true)) {
            return;
        }
        int accepted = 0;
        int limit = Math.max(1, config.getPeerSnapshotLimit());
        List<String> files = config.getPeerSnapshotFiles() != null ? config.getPeerSnapshotFiles() : List.of();
        for (String file : files) {
            if (accepted >= limit) {
                break;
            }
            accepted += loadPeerSnapshotFile(file, limit - accepted);
        }
        List<String> urls = config.getPeerSnapshotUrls() != null ? config.getPeerSnapshotUrls() : List.of();
        for (String url : urls) {
            if (accepted >= limit) {
                break;
            }
            accepted += loadPeerSnapshotUrl(url, limit - accepted);
        }
        if (accepted > 0) {
            log.info("Loaded {} peer-snapshot relay entries", accepted);
        }
    }

    private int loadPeerSnapshotFile(String file, int remainingLimit) {
        if (file == null || file.isBlank() || remainingLimit <= 0) {
            return 0;
        }
        Path path = Path.of(file.trim());
        try {
            if (Files.size(path) > MAX_SNAPSHOT_BYTES) {
                log.warn("Ignoring peer snapshot file {} because it exceeds {} bytes", file, MAX_SNAPSHOT_BYTES);
                return 0;
            }
            try (InputStream input = Files.newInputStream(path)) {
                return parsePeerSnapshot(file.trim(), bounded(input), remainingLimit);
            }
        } catch (Exception e) {
            log.warn("Failed to load peer snapshot file {}: {}", file, e.getMessage());
            return 0;
        }
    }

    private int loadPeerSnapshotUrl(String url, int remainingLimit) {
        if (url == null || url.isBlank() || remainingLimit <= 0) {
            return 0;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Failed to load peer snapshot URL {}: HTTP {}", url, response.statusCode());
                return 0;
            }
            try (InputStream input = response.body()) {
                return parsePeerSnapshot(url.trim(), bounded(input), remainingLimit);
            }
        } catch (Exception e) {
            log.warn("Failed to load peer snapshot URL {}: {}", url, e.getMessage());
            return 0;
        }
    }

    private void loadTopologyOnce() {
        String topologyFile = config.getTopologyFile();
        if (topologyFile == null || topologyFile.isBlank()) {
            return;
        }
        Path path = Path.of(topologyFile.trim());
        try (InputStream input = Files.newInputStream(path)) {
            JsonNode root = MAPPER.readTree(input);
            int accepted = 0;
            accepted += parseRootGroups(root.path("localRoots"), PeerSource.LOCAL_ROOT, true, "topology-local-root");
            accepted += parseRootGroups(root.path("publicRoots"), PeerSource.PUBLIC_ROOT, false, "topology-public-root");
            accepted += parseBootstrapPeers(root.path("bootstrapPeers"), path.getParent());

            JsonNode snapshotFile = root.path("peerSnapshotFile");
            if (snapshotFile.isTextual() && !snapshotFile.asText().isBlank()) {
                Path snapshotPath = Path.of(snapshotFile.asText().trim());
                if (!snapshotPath.isAbsolute() && path.getParent() != null) {
                    snapshotPath = path.getParent().resolve(snapshotPath).normalize();
                }
                accepted += loadPeerSnapshotFile(snapshotPath.toString(),
                        Math.max(1, config.getPeerSnapshotLimit()));
            }
            if (accepted > 0) {
                log.info("Loaded {} topology peer entries from {}", accepted, topologyFile);
            }
        } catch (Exception e) {
            log.warn("Failed to load topology file {}: {}", topologyFile, e.getMessage());
        }
    }

    private int parseRootGroups(JsonNode groups, PeerSource source, boolean defaultTrusted, String sourceId) {
        if (!groups.isArray()) {
            return 0;
        }
        int accepted = 0;
        for (JsonNode group : groups) {
            boolean trusted = group.path("trustable").asBoolean(defaultTrusted);
            JsonNode accessPoints = group.path("accessPoints");
            if (!accessPoints.isArray()) {
                continue;
            }
            for (JsonNode accessPoint : accessPoints) {
                String address = accessPoint.path("address").asText(null);
                int port = accessPoint.path("port").asInt(-1);
                if (acceptPeer(address, port, source, sourceId, trusted, source == PeerSource.LOCAL_ROOT ? 10_000 : 1_000)) {
                    accepted++;
                }
            }
        }
        return accepted;
    }

    private int parseBootstrapPeers(JsonNode peers, Path topologyDirectory) {
        if (!peers.isArray()) {
            return 0;
        }
        int accepted = 0;
        for (JsonNode peer : peers) {
            String address = peer.path("address").asText(null);
            int port = peer.path("port").asInt(-1);
            if (acceptPeer(address, port, PeerSource.BOOTSTRAP, "topology-bootstrap", false, 5_000)) {
                accepted++;
            }
        }
        return accepted;
    }

    private int parsePeerSnapshot(String source, InputStream input, int remainingLimit) throws Exception {
        JsonNode root = MAPPER.readTree(input);
        long snapshotMagic = root.path("NetworkMagic").asLong(protocolMagic);
        if (snapshotMagic != protocolMagic) {
            log.warn("Ignoring peer snapshot {} because NetworkMagic={} does not match configured protocol magic={}",
                    source, snapshotMagic, protocolMagic);
            return 0;
        }
        JsonNode pools = root.path("bigLedgerPools");
        if (!pools.isArray()) {
            log.warn("Ignoring peer snapshot {} because bigLedgerPools is missing", source);
            return 0;
        }

        int accepted = 0;
        Set<String> seen = new HashSet<>();
        for (JsonNode pool : pools) {
            if (accepted >= remainingLimit) {
                break;
            }
            int score = scoreFromRelativeStake(pool.path("relativeStake").asDouble(0));
            JsonNode relays = pool.path("relays");
            if (!relays.isArray()) {
                continue;
            }
            for (JsonNode relay : relays) {
                if (accepted >= remainingLimit) {
                    break;
                }
                String address = relay.path("address").asText(null);
                int port = relay.path("port").asInt(-1);
                if (address == null || !addressPolicy.allows(PeerSource.BOOTSTRAP, address, port)) {
                    continue;
                }
                String key = address.trim().toLowerCase(java.util.Locale.ROOT) + ":" + port;
                if (!seen.add(key)) {
                    continue;
                }
                if (acceptPeer(address, port, PeerSource.BOOTSTRAP, "peer-snapshot", false, score)) {
                    accepted++;
                }
            }
        }
        log.info("Peer snapshot {} accepted {} relay entries", source, accepted);
        return accepted;
    }

    private int scoreFromRelativeStake(double relativeStake) {
        if (relativeStake <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.round(relativeStake * 1_000_000));
    }

    private void startSeedDiscovery(String host, int port) {
        PeerDiscovery discovery = new PeerDiscovery(host, port, protocolMagic, 32, nodeClientConfig);
        try {
            discovery.start(addresses -> onPeerAddresses(host, port, addresses));
            runningDiscoveries.add(discovery);
            log.info("Started peer-sharing discovery from {}:{}", host, port);
        } catch (Exception e) {
            log.warn("Failed to start peer-sharing discovery from {}:{}: {}", host, port, e.getMessage());
            try {
                discovery.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    private void onPeerAddresses(String seedHost, int seedPort, List<PeerAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            log.debug("Peer-sharing discovery from {}:{} returned no peers", seedHost, seedPort);
            return;
        }
        int accepted = 0;
        for (PeerAddress address : addresses) {
            if (address == null || !addressPolicy.allows(PeerSource.GOSSIP,
                    address.getAddress(), address.getPort())) {
                continue;
            }
            if (acceptPeer(address.getAddress(), address.getPort(), PeerSource.GOSSIP,
                    "peer-sharing:" + seedHost + ":" + seedPort, false, 0)) {
                accepted++;
            }
        }
        log.info("Peer-sharing discovery from {}:{} accepted {} of {} advertised peers",
                seedHost, seedPort, accepted, addresses.size());
    }

    private boolean acceptPeer(String address,
                               int port,
                               PeerSource source,
                               String sourceId,
                               boolean trusted,
                               int score) {
        if (address == null || !addressPolicy.allows(source, address, port)) {
            return false;
        }
        String prefix = "peer-snapshot".equals(sourceId) ? "snapshot" : switch (source) {
            case LOCAL_ROOT -> "local-root";
            case PUBLIC_ROOT -> "public-root";
            case BOOTSTRAP -> "bootstrap";
            case LEDGER -> "ledger";
            case INBOUND -> "inbound";
            case STATIC_UPSTREAM -> "static";
            case GOSSIP -> "discovered";
        };
        discoveredPeerConsumer.accept(new PeerStoreEntry(
                prefix + "-" + address + ":" + port,
                address,
                port,
                source.configValue(),
                trusted,
                score));
        return true;
    }

    private InputStream bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_SNAPSHOT_BYTES + 1);
        if (bytes.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("snapshot exceeds " + MAX_SNAPSHOT_BYTES + " bytes");
        }
        return new ByteArrayInputStream(bytes);
    }
}
