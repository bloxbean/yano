package com.bloxbean.cardano.yano.p2p.discovery;

import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import com.bloxbean.cardano.yano.p2p.governor.PeerAddressPolicy;
import com.bloxbean.cardano.yano.p2p.governor.PeerDescriptor;
import com.bloxbean.cardano.yano.p2p.governor.PeerSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YaciPeerDiscoveryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void topologyFileSeedsPeerStoreEntries() throws Exception {
        Path topology = tempDir.resolve("topology.json");
        Files.writeString(topology, """
                {
                  "localRoots": [
                    {
                      "accessPoints": [
                        { "address": "127.0.0.1", "port": 3001 }
                      ],
                      "trustable": true,
                      "advertise": false,
                      "hotValency": 1,
                      "warmValency": 1
                    }
                  ],
                  "publicRoots": [
                    {
                      "accessPoints": [
                        { "address": "relay.example.com", "port": 3002 }
                      ],
                      "hotValency": 1,
                      "warmValency": 1
                    }
                  ],
                  "bootstrapPeers": [
                    { "address": "bootstrap.example.com", "port": 3003 }
                  ]
                }
                """);
        var config = UpstreamDiscoveryConfig.builder()
                .enabled(true)
                .topologyFile(topology.toString())
                .build();
        var discovered = new ArrayList<PeerDescriptor>();

        try (var service = new YaciPeerDiscoveryService(
                1,
                config,
                new PeerAddressPolicy(config),
                discovered::add)) {
            service.start();
        }

        assertThat(discovered)
                .extracting(PeerDescriptor::source)
                .contains(PeerSource.LOCAL_ROOT, PeerSource.PUBLIC_ROOT, PeerSource.BOOTSTRAP);
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
        var discovered = new ArrayList<PeerDescriptor>();

        try (var service = new YaciPeerDiscoveryService(
                1,
                config,
                new PeerAddressPolicy(config),
                discovered::add)) {
            service.start();
        }

        assertThat(discovered)
                .extracting(PeerDescriptor::id)
                .containsExactly(
                        "relay-a.example.com:3001",
                        "relay-b.example.com:3002");
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
        var discovered = new ArrayList<PeerDescriptor>();

        try (var service = new YaciPeerDiscoveryService(
                1,
                config,
                new PeerAddressPolicy(config),
                discovered::add)) {
            service.start();
        }

        assertThat(discovered).isEmpty();
    }
}
