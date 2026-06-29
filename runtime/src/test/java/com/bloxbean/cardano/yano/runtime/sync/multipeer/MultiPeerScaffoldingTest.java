package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiPeerScaffoldingTest {
    @TempDir
    Path tempDir;

    @Test
    void singleUntrustedLongerCandidateIsObserveOnly() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader candidate = candidate("peer-a", 101, "a", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(candidate)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.OBSERVE);
        assertThat(decision.selected()).isEqualTo(candidate);
    }

    @Test
    void trustedLongerCandidateCanBeAdopted() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader candidate = candidate("peer-a", 101, "a", true);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(candidate)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
    }

    @Test
    void untrustedQuorumCanBeAdopted() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader a = candidate("peer-a", 101, "same", false);
        CandidateHeader b = candidate("peer-b", 101, "same", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(a, b)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
    }

    @Test
    void untrustedLongerCandidateDoesNotBlockTrustedCandidate() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader trusted = candidate("trusted", 101, "trusted-hash", true);
        CandidateHeader untrusted = candidate("untrusted", 102, "untrusted-hash", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(trusted, untrusted)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
        assertThat(decision.selected()).isEqualTo(trusted);
    }

    @Test
    void headerFanInStoresCandidatesWithoutCanonicalState() {
        var store = new InMemoryCandidateHeaderStore();
        var fanIn = new HeaderFanIn(store);
        CandidateHeader header = candidate("peer-a", 101, "a", true);

        fanIn.onCandidateHeader(header);

        assertThat(store.get("a")).contains(header);
        assertThat(fanIn.candidatesAfter(100)).containsExactly(header);
    }

    @Test
    void candidateStoreKeepsSameHashObservationsPerPeerForQuorum() {
        var store = new InMemoryCandidateHeaderStore();
        var fanIn = new HeaderFanIn(store);
        CandidateHeader a = candidate("peer-a", 101, "same", false);
        CandidateHeader b = candidate("peer-b", 101, "same", false);

        fanIn.onCandidateHeader(a);
        fanIn.onCandidateHeader(b);

        assertThat(store.all()).containsExactlyInAnyOrder(a, b);

        var decision = new TrustedOrQuorumCandidateWithinRollbackWindow().evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                fanIn.candidatesAfter(100)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
    }

    @Test
    void candidateStoreDeduplicatesSamePeerSameHash() {
        var store = new InMemoryCandidateHeaderStore();
        CandidateHeader older = new CandidateHeader("peer-a", 1100, 100, "same", null, false, 1);
        CandidateHeader newer = new CandidateHeader("peer-a", 1101, 100, "same", null, false, 2);

        store.put(older);
        store.put(newer);

        assertThat(store.all()).containsExactly(newer);
        assertThat(store.get("same")).contains(newer);
    }

    @Test
    void candidateStoreEvictsOldestObservationsWhenBounded() {
        var store = new InMemoryCandidateHeaderStore(2);
        CandidateHeader old = new CandidateHeader("peer-a", 1000, 100, "old", null, false, 1);
        CandidateHeader middle = new CandidateHeader("peer-b", 1001, 101, "middle", null, false, 2);
        CandidateHeader newest = new CandidateHeader("peer-c", 1002, 102, "newest", null, false, 3);

        store.put(old);
        store.put(middle);
        store.put(newest);

        assertThat(store.all()).containsExactly(middle, newest);
        assertThat(store.get("old")).isEmpty();
    }

    @Test
    void peerGovernorPrefersTrustedAndHigherScore() {
        var store = new InMemoryPeerStore();
        store.put(new PeerStoreEntry("low", "low", 3001, "discovered", true, 1));
        store.put(new PeerStoreEntry("high", "high", 3001, "local-root", true, 10));
        store.put(new PeerStoreEntry("untrusted", "untrusted", 3001, "discovered", false, 100));

        var governor = new PeerGovernor(store);

        assertThat(governor.selectHotPeers(2))
                .extracting(PeerStoreEntry::id)
                .containsExactly("high", "low");
    }

    @Test
    void fileBackedPeerStoreReloadsPersistedPeers() {
        Path file = tempDir.resolve("upstream-peers.json");
        var store = new FileBackedPeerStore(file);
        store.put(new PeerStoreEntry("peer-a", "relay-a.example.com", 3001, "peer-sharing", false, 10));
        store.put(new PeerStoreEntry("peer-b", "relay-b.example.com", 3002, "local-root", true, 20));

        var reloaded = new FileBackedPeerStore(file);

        assertThat(reloaded.all())
                .extracting(PeerStoreEntry::id)
                .containsExactly("peer-a", "peer-b");
    }

    @Test
    void candidateHeaderListenerStoresObservedHeadersOnlyAsCandidates() {
        var store = new InMemoryCandidateHeaderStore();
        var listener = new CandidateHeaderListener("peer-a", true, new HeaderFanIn(store), header -> { });

        listener.rollforward(null, BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .slot(100)
                        .blockNumber(10)
                        .prevHash("prev")
                        .blockHash("hash")
                        .build())
                .build(), new byte[] {1});

        assertThat(listener.headersObserved()).isEqualTo(1);
        assertThat(store.get("hash")).isPresent();
    }

    @Test
    void peerAddressPolicyRejectsPrivateAddressesUnlessAllowed() {
        var denied = new PeerAddressPolicy(UpstreamDiscoveryConfig.builder().build());
        var allowed = new PeerAddressPolicy(UpstreamDiscoveryConfig.builder()
                .allowPrivateAddresses(true)
                .build());

        assertThat(denied.allows("127.0.0.1", 3001)).isFalse();
        assertThat(allowed.allows("127.0.0.1", 3001)).isTrue();
        assertThat(PeerAddressPolicy.parseEndpoint("relay.example.com:3001", -1))
                .contains(new PeerAddressPolicy.HostPort("relay.example.com", 3001));
    }

    @Test
    void peerSnapshotFileSeedsPeerStoreEntries() throws Exception {
        Path snapshot = tempDir.resolve("peer-snapshot.json");
        Files.writeString(snapshot, """
                {
                  "NetworkMagic": 1,
                  "bigLedgerPools": [
                    {
                      "relativeStake": 0.25,
                      "relays": [
                        { "address": "relay-a.example.com", "port": 3001 },
                        { "address": "relay-b.example.com", "port": 3002 }
                      ]
                    },
                    {
                      "relativeStake": 0.10,
                      "relays": [
                        { "address": "relay-c.example.com", "port": 3003 }
                      ]
                    }
                  ]
                }
                """);
        var config = UpstreamDiscoveryConfig.builder()
                .enabled(true)
                .peerSnapshotFiles(List.of(snapshot.toString()))
                .peerSnapshotLimit(2)
                .build();
        var discovered = new ArrayList<PeerStoreEntry>();

        try (var service = new YaciPeerDiscoveryService(
                1,
                config,
                new PeerAddressPolicy(config),
                discovered::add)) {
            service.start();
        }

        assertThat(discovered)
                .extracting(PeerStoreEntry::id)
                .containsExactly(
                        "snapshot-relay-a.example.com:3001",
                        "snapshot-relay-b.example.com:3002");
        assertThat(discovered.getFirst().score()).isEqualTo(250_000);
    }

    @Test
    void peerSnapshotWithWrongNetworkMagicIsIgnored() throws Exception {
        Path snapshot = tempDir.resolve("wrong-network-peer-snapshot.json");
        Files.writeString(snapshot, """
                {
                  "NetworkMagic": 2,
                  "bigLedgerPools": [
                    {
                      "relativeStake": 0.25,
                      "relays": [
                        { "address": "relay-a.example.com", "port": 3001 }
                      ]
                    }
                  ]
                }
                """);
        var config = UpstreamDiscoveryConfig.builder()
                .enabled(true)
                .peerSnapshotFiles(List.of(snapshot.toString()))
                .build();
        var discovered = new ArrayList<PeerStoreEntry>();

        try (var service = new YaciPeerDiscoveryService(
                1,
                config,
                new PeerAddressPolicy(config),
                discovered::add)) {
            service.start();
        }

        assertThat(discovered).isEmpty();
    }

    private static CandidateHeader candidate(String peerId, long blockNumber, String hash, boolean trusted) {
        return new CandidateHeader(peerId, 1000 + blockNumber, blockNumber, hash, null, trusted, 1);
    }
}
