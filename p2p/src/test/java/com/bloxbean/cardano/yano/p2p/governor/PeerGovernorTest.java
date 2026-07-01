package com.bloxbean.cardano.yano.p2p.governor;

import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamGovernorConfig;
import com.bloxbean.cardano.yano.p2p.connection.ConnectionDirection;
import com.bloxbean.cardano.yano.p2p.connection.ConnectionKey;
import com.bloxbean.cardano.yano.p2p.connection.ConnectionState;
import com.bloxbean.cardano.yano.p2p.connection.ProtocolCapabilities;
import com.bloxbean.cardano.yano.p2p.connection.RelayConnectionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeerGovernorTest {
    @TempDir
    Path tempDir;

    @Test
    void peerGovernorPrefersTrustedAndHigherScore() {
        var store = new InMemoryPeerStore();
        var governor = new PeerGovernor(store);
        governor.addOrUpdatePeer(descriptor("low", 3001, PeerSource.GOSSIP, true, 1));
        governor.addOrUpdatePeer(descriptor("high", 3001, PeerSource.LOCAL_ROOT, true, 10));
        governor.addOrUpdatePeer(descriptor("untrusted", 3001, PeerSource.GOSSIP, false, 100));

        assertThat(governor.selectHotPeers(2))
                .extracting(PeerStoreEntry::id)
                .containsExactly("high:3001", "low:3001");
    }

    @Test
    void peerGovernorBoundsGossipButPreservesConfiguredPeers() {
        var store = new InMemoryPeerStore();
        var governor = new PeerGovernor(store,
                UpstreamGovernorConfig.builder()
                        .targetCold(2)
                        .targetWarm(0)
                        .targetHot(1)
                        .build());

        governor.addOrUpdatePeer(descriptor("relay-a.example.com", 3001, PeerSource.LOCAL_ROOT, true, 10));
        governor.addOrUpdatePeer(descriptor("relay-b.example.com", 3001, PeerSource.GOSSIP, false, 1));
        governor.addOrUpdatePeer(descriptor("relay-c.example.com", 3001, PeerSource.GOSSIP, false, 100));

        assertThat(governor.peerStoreEntries())
                .extracting(PeerStoreEntry::id)
                .containsExactlyInAnyOrder("relay-a.example.com:3001", "relay-c.example.com:3001");
    }

    @Test
    void fileBackedPeerStoreReloadsPersistedPeers() throws Exception {
        Path file = tempDir.resolve("upstream-peers.json");
        var store = new FileBackedPeerStore(file);
        store.put(new PeerStoreEntry("peer-a", "relay-a.example.com", 3001, "peer-sharing", false, 10));
        store.put(new PeerStoreEntry("peer-b", "relay-b.example.com", 3002, "local-root", true, 20));

        var reloaded = new FileBackedPeerStore(file);

        assertThat(reloaded.all())
                .extracting(PeerStoreEntry::id)
                .containsExactly("peer-a", "peer-b");
        assertThat(Files.readString(file)).doesNotContain("score");
    }

    @Test
    void peerGovernorCorrelatesConnectionEventsByEndpoint() {
        var governor = new PeerGovernor(new InMemoryPeerStore());
        governor.addOrUpdatePeer(descriptor("relay-a.example.com", 3001, PeerSource.BOOTSTRAP, false, 250_000));

        governor.onConnectionEvent(new RelayConnectionEvent(
                "out-1",
                ConnectionDirection.OUTBOUND,
                ConnectionState.FAILED,
                ConnectionKey.of("relay-a.example.com", 3001),
                ProtocolCapabilities.unknown(),
                "connect failed",
                System.currentTimeMillis()));

        PeerGovernorSnapshot snapshot = governor.snapshot();
        assertThat(snapshot.knownPeerCount()).isEqualTo(1);
        assertThat(snapshot.backoffPeerCount()).isEqualTo(1);
        assertThat(governor.hotPeers(PeerUse.CHAIN_SYNC, 1)).isEmpty();
    }

    @Test
    void peerGovernorPersistsStableEntriesWithoutPersistingConnectionEvents() {
        var store = new CountingPeerStore();
        var governor = new PeerGovernor(store);

        governor.addOrUpdatePeer(descriptor("relay-cache.example.com", 3001, PeerSource.BOOTSTRAP, false, 10));

        assertThat(store.replaceCalls).isEqualTo(1);
        assertThat(store.entries)
                .extracting(PeerStoreEntry::id)
                .containsExactly("relay-cache.example.com:3001");

        governor.onConnectionEvent(new RelayConnectionEvent(
                "out-1",
                ConnectionDirection.OUTBOUND,
                ConnectionState.FAILED,
                ConnectionKey.of("relay-cache.example.com", 3001),
                ProtocolCapabilities.unknown(),
                "connect failed",
                System.currentTimeMillis()));

        assertThat(store.replaceCalls).isEqualTo(1);
        assertThat(store.entries).allSatisfy(entry ->
                assertThat(entry).extracting("id", "host", "port", "source", "trusted")
                        .containsExactly("relay-cache.example.com:3001",
                                "relay-cache.example.com", 3001, "bootstrap", false));

        governor.addOrUpdatePeer(descriptor("relay-cache-2.example.com", 3001, PeerSource.GOSSIP, false, 5));
        assertThat(store.replaceCalls).isEqualTo(1);

        governor.flushPeerStore();

        assertThat(store.replaceCalls).isEqualTo(2);
        assertThat(store.entries)
                .extracting(PeerStoreEntry::id)
                .containsExactly("relay-cache-2.example.com:3001", "relay-cache.example.com:3001");
    }

    @Test
    void peerGovernorRemovesInboundEphemeralPeerOnClose() {
        var governor = new PeerGovernor(new InMemoryPeerStore());
        ConnectionKey key = ConnectionKey.of("192.0.2.10", 49152);

        governor.onConnectionEvent(new RelayConnectionEvent(
                "in-1",
                ConnectionDirection.INBOUND,
                ConnectionState.ESTABLISHED,
                key,
                ProtocolCapabilities.unknown(),
                null,
                System.currentTimeMillis()));
        assertThat(governor.snapshot().inboundPeerCount()).isEqualTo(1);

        governor.onConnectionEvent(new RelayConnectionEvent(
                "in-1",
                ConnectionDirection.INBOUND,
                ConnectionState.CLOSED,
                key,
                ProtocolCapabilities.unknown(),
                "closed",
                System.currentTimeMillis()));

        assertThat(governor.snapshot().knownPeerCount()).isZero();
    }

    @Test
    void peerGovernorRemovesExpiredGossipPeer() {
        var governor = new PeerGovernor(new InMemoryPeerStore());
        long now = System.currentTimeMillis();

        PeerDescriptor admitted = governor.addOrUpdatePeer(new PeerDescriptor(
                "expired",
                "relay-expired.example.com",
                3001,
                PeerSource.GOSSIP,
                "peer-sharing",
                false,
                false,
                true,
                1,
                1,
                now - 10_000,
                now - 10_000,
                now - 1,
                1));

        assertThat(admitted).isNull();
        assertThat(governor.snapshot().knownPeerCount()).isZero();
    }

    @Test
    void peerAddressPolicyRejectsPrivateAddressesUnlessAllowed() {
        var denied = new PeerAddressPolicy(UpstreamDiscoveryConfig.builder().build());
        var allowed = new PeerAddressPolicy(UpstreamDiscoveryConfig.builder()
                .allowPrivateAddresses(true)
                .build());

        assertThat(denied.allows("127.0.0.1", 3001)).isFalse();
        assertThat(allowed.allows("127.0.0.1", 3001)).isTrue();
        assertThat(denied.allows(PeerSource.LOCAL_ROOT, "127.0.0.1", 3001)).isTrue();
        assertThat(PeerAddressPolicy.parseEndpoint("[::1]:3001", -1))
                .contains(new PeerAddressPolicy.HostPort("::1", 3001));
        assertThat(PeerAddressPolicy.parseEndpoint("relay.example.com:3001", -1))
                .contains(new PeerAddressPolicy.HostPort("relay.example.com", 3001));
    }

    private static PeerDescriptor descriptor(String host, int port, PeerSource source, boolean trusted, int score) {
        long now = System.currentTimeMillis();
        return new PeerDescriptor(
                host + ":" + port,
                host,
                port,
                source,
                source.configValue(),
                trusted,
                source == PeerSource.LOCAL_ROOT || source == PeerSource.STATIC_UPSTREAM,
                source != PeerSource.INBOUND,
                1,
                1,
                now,
                now,
                null,
                score);
    }

    private static final class CountingPeerStore implements PeerStore {
        private List<PeerStoreEntry> entries = List.of();
        private int replaceCalls;

        @Override
        public void put(PeerStoreEntry peer) {
            entries = List.of(peer);
        }

        @Override
        public void replaceAll(List<PeerStoreEntry> peers) {
            replaceCalls++;
            entries = peers != null ? List.copyOf(peers) : List.of();
        }

        @Override
        public List<PeerStoreEntry> all() {
            return entries;
        }
    }
}
