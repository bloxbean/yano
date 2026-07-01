package com.bloxbean.cardano.yano.runtime.sync;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.SyncPhase;
import com.bloxbean.cardano.yano.api.config.ChainSelectionConfig;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.UpstreamConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamDiscoveryConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamFailoverConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamGovernorConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPeerConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamPreset;
import com.bloxbean.cardano.yano.api.config.UpstreamSyncConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamTxConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystem;
import com.bloxbean.cardano.yano.p2p.peer.PeerEndpoint;
import com.bloxbean.cardano.yano.p2p.peer.PeerRecoveryReason;
import com.bloxbean.cardano.yano.runtime.server.ServeSubsystem;
import com.bloxbean.cardano.yano.runtime.storage.ChainStorageSubsystem;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.DefaultTxDiffusion;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxCatalog;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxDiffusion;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxDiffusionMode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class SyncSubsystemTest {

    @Test
    void ownsProgressCountersAndCloseHealth() {
        YanoConfig config = YanoConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42L)
                .serverPort(0)
                .enableServer(false)
                .enableClient(false)
                .useRocksDB(false)
                .fullSyncThreshold(1_800)
                .enablePipelinedSync(true)
                .headerPipelineDepth(10)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .build();
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.account-state.enabled", false,
                "yano.account-history.enabled", false));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ChainStorageSubsystem chainStorage = new ChainStorageSubsystem(
                config,
                options,
                LoggerFactory.getLogger(SyncSubsystemTest.class));
        LedgerStateSubsystem ledgerState = new LedgerStateSubsystem(
                config,
                options,
                chainStorage.chainState(),
                new NoopEventBus(),
                LoggerFactory.getLogger(SyncSubsystemTest.class),
                null,
                null,
                null,
                null,
                () -> null,
                () -> null,
                () -> null,
                null);
        ServeSubsystem serve = new ServeSubsystem(
                0,
                config.getProtocolMagic(),
                chainStorage.chainState(),
                noopTransactionAdmission(),
                false,
                LoggerFactory.getLogger(SyncSubsystemTest.class));
        SyncSubsystem sync = new SyncSubsystem(
                config,
                chainStorage.chainState(),
                new NoopEventBus(),
                scheduler,
                serve,
                ledgerState,
                chainStorage,
                () -> false,
                ledgerState::epochParamProvider,
                ledgerState::currentGenesisBootstrapData,
                config.getRemoteHost(),
                config.getRemotePort(),
                config.getProtocolMagic(),
                LoggerFactory.getLogger(SyncSubsystemTest.class));

        try {
            assertThat(sync.name()).isEqualTo("sync");
            assertThat(sync.health().healthy()).isTrue();
            assertThat(sync.isSyncing()).isFalse();
            assertThat(sync.isInitialSyncComplete()).isFalse();
            assertThat(sync.syncPhase()).isEqualTo(SyncPhase.INITIAL_SYNC);
            assertThat(sync.stopForShutdown()).isFalse();

            sync.updateSyncProgress(42L, 7L);

            assertThat(sync.blocksProcessed()).isEqualTo(1L);
            assertThat(sync.lastProcessedSlot()).isEqualTo(42L);

            sync.close();

            assertThat(sync.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);
        } finally {
            sync.close();
            ledgerState.close();
            serve.close();
            chainStorage.closeAfterRuntimeDrain(false);
            scheduler.shutdownNow();
        }
    }

    @Test
    void serverOnlyModeDoesNotRequireRemoteHost() {
        YanoConfig config = serverOnlyConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null);

        try {
            SyncSubsystem sync = runtime.sync(null);

            assertThat(sync.health().healthy()).isTrue();
            assertThat(sync.isSyncing()).isFalse();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void stopCancelsPendingIntersectionTransition() {
        YanoConfig config = serverOnlyConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null);

        try {
            SyncSubsystem sync = runtime.sync(null);
            sync.setRollbackClassificationTimeoutMillis(60_000L);

            sync.onIntersectionFound();
            assertThat(sync.syncPhase()).isEqualTo(SyncPhase.INTERSECT_PHASE);
            assertThat(sync.hasPendingIntersectionTransition()).isTrue();

            sync.stopForShutdown();

            assertThat(sync.hasPendingIntersectionTransition()).isFalse();
            assertThat(sync.syncPhase()).isEqualTo(SyncPhase.INTERSECT_PHASE);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void clientStartupCancelsPendingIntersectionTransition() {
        YanoConfig config = serverOnlyConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null);

        try {
            SyncSubsystem sync = runtime.sync(null);
            sync.setRollbackClassificationTimeoutMillis(60_000L);

            sync.onIntersectionFound();
            assertThat(sync.hasPendingIntersectionTransition()).isTrue();

            sync.startClientSync();

            assertThat(sync.hasPendingIntersectionTransition()).isFalse();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void recoverableStartupRecoveryFailureDoesNotMarkPeerTerminal() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    throw new RuntimeException("temporary");
                });

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());

            sync.startClientSync();

            assertThat(sync.peerRecoverySnapshot().terminal()).isFalse();
            assertThat(sync.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(sync.currentPeerSessionStatus().terminalFailureMessage()).isNull();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void terminalStartupRecoveryFailureStopsSyncAndReportsDownHealth() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    throw new RuntimeException("boom");
                });

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());

            for (int i = 0; i < 10; i++) {
                sync.startClientSync();
            }

            assertThat(sync.isSyncing()).isFalse();
            assertThat(sync.peerRecoverySnapshot().terminal()).isTrue();
            assertThat(sync.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);
            assertThat(sync.currentPeerSessionStatus().lastRecoveryReason())
                    .isEqualTo(PeerRecoveryReason.STARTUP_FAILED);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void trustedFailoverAdvancesActivePeerAfterStartupFailure() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.TRUSTED_FAILOVER)
                        .peers(List.of(
                                upstreamPeer("bad", "bad-relay", 3001, 0),
                                upstreamPeer("good", "good-relay", 3002, 1)))
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> attempts = new ArrayList<>();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    attempts.add(endpoint.displayName());
                    throw new RuntimeException("dial failed");
                });

        try {
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();

            waitForAttempt(attempts, "good-relay:3002");
            assertThat(attempts).startsWith("bad-relay:3001", "good-relay:3002");
            assertThat(sync.upstreamStatus().configuredPeerCount()).isEqualTo(2);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void staticMultiStartsObserverPeerWhenFanInIsAlways() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.STATIC_MULTI)
                        .peers(List.of(
                                upstreamPeer("peer-a", "peer-a", 3001, 0),
                                upstreamPeer("peer-b", "peer-b", 3002, 1)))
                        .sync(UpstreamSyncConfig.builder()
                                .fanInStart("always")
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(2)
                                .build())
                        .selection(ChainSelectionConfig.builder()
                                .quorum(2)
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> new RecordingPeerClient(endpoint, point, headerSyncStarts));

        try {
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();

            waitForAttempt(headerSyncStarts, "peer-b:3002");
            assertThat(sync.upstreamStatus().observerPeerCount()).isEqualTo(1);
            assertThat(sync.upstreamStatus().hotPeerCount()).isEqualTo(2);
            assertThat(sync.upstreamStatus().multiPeerObservationOnly()).isFalse();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void selectionRollbackWindowDefaultsToGenesisSecurityWindowInSlots() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.STATIC_MULTI)
                        .peers(List.of(
                                upstreamPeer("peer-a", "peer-a", 3001, 0),
                                upstreamPeer("peer-b", "peer-b", 3002, 1)))
                        .selection(ChainSelectionConfig.builder()
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null,
                () -> epochParams(100L, 1.0D));

        try {
            SyncSubsystem sync = runtime.sync(null);

            assertThat(sync.selectionRollbackWindowSlots()).isEqualTo(100L);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void explicitSelectionRollbackWindowOverridesGenesisDefault() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.STATIC_MULTI)
                        .peers(List.of(
                                upstreamPeer("peer-a", "peer-a", 3001, 0),
                                upstreamPeer("peer-b", "peer-b", 3002, 1)))
                        .selection(ChainSelectionConfig.builder()
                                .rollbackWindowSlots(321L)
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null,
                () -> epochParams(100L, 1.0D));

        try {
            SyncSubsystem sync = runtime.sync(null);

            assertThat(sync.selectionRollbackWindowSlots()).isEqualTo(321L);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void p2pRelayCanBootstrapActivePeerFromPeerSnapshotWithoutConfiguredPeers() throws Exception {
        Path snapshot = Files.createTempFile("yano-peer-snapshot", ".json");
        Files.writeString(snapshot, """
                {
                  "NetworkMagic": 42,
                  "bigLedgerPools": [
                    {
                      "relativeStake": 0.10,
                      "relays": [
                        { "address": "relay-a.example.com", "port": 3001 }
                      ]
                    }
                  ]
                }
                """);
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost("legacy-remote.example.com")
                .remotePort(3000)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.P2P_RELAY)
                        .discovery(UpstreamDiscoveryConfig.builder()
                                .enabled(true)
                                .peerSnapshotFiles(List.of(snapshot.toString()))
                                .peerSnapshotLimit(1)
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(1)
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> new RecordingPeerClient(endpoint, point, headerSyncStarts));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());

            sync.startClientSync();

            waitForAttempt(headerSyncStarts, "relay-a.example.com:3001");
            assertThat(headerSyncStarts).doesNotContain("legacy-remote.example.com:3000");
            assertThat(sync.upstreamStatus().configuredPeerCount()).isEqualTo(0);
            assertThat(sync.upstreamStatus().knownPeerCount()).isEqualTo(1);
            assertThat(sync.upstreamStatus().activePeerName()).isEqualTo("relay-a.example.com:3001");
        } finally {
            runtime.close();
            scheduler.shutdownNow();
            Files.deleteIfExists(snapshot);
        }
    }

    @Test
    void p2pRelayFallsBackToDiscoveredPeerWhenConfiguredPeerFails() throws Exception {
        Path snapshot = Files.createTempFile("yano-peer-snapshot", ".json");
        Files.writeString(snapshot, """
                {
                  "NetworkMagic": 42,
                  "bigLedgerPools": [
                    {
                      "relativeStake": 0.10,
                      "relays": [
                        { "address": "relay-b.example.com", "port": 3002 }
                      ]
                    }
                  ]
                }
                """);
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.P2P_RELAY)
                        .peers(List.of(upstreamPeer("bad", "bad-relay.example.com", 3001, 0)))
                        .discovery(UpstreamDiscoveryConfig.builder()
                                .enabled(true)
                                .peerSnapshotFiles(List.of(snapshot.toString()))
                                .peerSnapshotLimit(1)
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(1)
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    attempts.add(endpoint.displayName());
                    return new RecordingPeerClient(
                            endpoint,
                            point,
                            headerSyncStarts,
                            Collections.synchronizedList(new ArrayList<>()),
                            endpoint.host().equals("bad-relay.example.com"));
                });

        try {
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();

            waitForAttempt(attempts, "relay-b.example.com:3002");
            waitForAttempt(headerSyncStarts, "relay-b.example.com:3002");
            assertThat(attempts).startsWith("bad-relay.example.com:3001", "relay-b.example.com:3002");
            assertThat(sync.upstreamStatus().activePeerName()).isEqualTo("relay-b.example.com:3002");
            assertThat(sync.upstreamStatus().knownPeerCount()).isEqualTo(2);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
            Files.deleteIfExists(snapshot);
        }
    }

    @Test
    void p2pRelaySkipsRecentlyFailedConfiguredPeerWhenDiscoveredPeerAlsoFails() throws Exception {
        Path snapshot = Files.createTempFile("yano-peer-snapshot", ".json");
        Files.writeString(snapshot, """
                {
                  "NetworkMagic": 42,
                  "bigLedgerPools": [
                    {
                      "relativeStake": 0.10,
                      "relays": [
                        { "address": "relay-b.example.com", "port": 3002 },
                        { "address": "relay-c.example.com", "port": 3003 }
                      ]
                    }
                  ]
                }
                """);
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.P2P_RELAY)
                        .peers(List.of(upstreamPeer("bad", "bad-relay.example.com", 3001, 0)))
                        .discovery(UpstreamDiscoveryConfig.builder()
                                .enabled(true)
                                .peerSnapshotFiles(List.of(snapshot.toString()))
                                .peerSnapshotLimit(2)
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(1)
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    attempts.add(endpoint.displayName());
                    boolean failOnStart = endpoint.host().equals("bad-relay.example.com")
                            || endpoint.host().equals("relay-b.example.com");
                    return new RecordingPeerClient(
                            endpoint,
                            point,
                            headerSyncStarts,
                            Collections.synchronizedList(new ArrayList<>()),
                            failOnStart);
                });

        try {
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();

            waitForAttempt(attempts, "relay-b.example.com:3002");
            waitForActivePeer(sync, "relay-c.example.com:3003");
            waitForAttempt(attempts, "relay-c.example.com:3003");
            assertThat(attempts).startsWith("bad-relay.example.com:3001", "relay-b.example.com:3002");
            assertThat(sync.upstreamStatus().activePeerName()).isEqualTo("relay-c.example.com:3003");
        } finally {
            runtime.close();
            scheduler.shutdownNow();
            Files.deleteIfExists(snapshot);
        }
    }

    @Test
    void p2pRelayTriesUnfailedDiscoveredPeersBeforeRetryingFailedTrustedPeer() throws Exception {
        Path snapshot = Files.createTempFile("yano-peer-snapshot", ".json");
        Files.writeString(snapshot, """
                {
                  "NetworkMagic": 42,
                  "bigLedgerPools": [
                    {
                      "relativeStake": 0.10,
                      "relays": [
                        { "address": "relay-b.example.com", "port": 3002 },
                        { "address": "relay-c.example.com", "port": 3003 }
                      ]
                    }
                  ]
                }
                """);
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.P2P_RELAY)
                        .peers(List.of(upstreamPeer("bad", "bad-relay.example.com", 3001, 0)))
                        .discovery(UpstreamDiscoveryConfig.builder()
                                .enabled(true)
                                .peerSnapshotFiles(List.of(snapshot.toString()))
                                .peerSnapshotLimit(2)
                                .build())
                        .failover(UpstreamFailoverConfig.builder()
                                .cooldownMs(0)
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(1)
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    attempts.add(endpoint.displayName());
                    boolean failOnStart = endpoint.host().equals("bad-relay.example.com")
                            || endpoint.host().equals("relay-b.example.com");
                    return new RecordingPeerClient(
                            endpoint,
                            point,
                            headerSyncStarts,
                            Collections.synchronizedList(new ArrayList<>()),
                            failOnStart);
                });

        try {
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();

            waitForAttempt(attempts, "relay-b.example.com:3002");
            waitForActivePeer(sync, "relay-c.example.com:3003");
            waitForAttempt(attempts, "relay-c.example.com:3003");
            assertThat(attempts).startsWith(
                    "bad-relay.example.com:3001",
                    "relay-b.example.com:3002");
            assertThat(Collections.frequency(attempts, "bad-relay.example.com:3001")).isEqualTo(1);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
            Files.deleteIfExists(snapshot);
        }
    }

    @Test
    void allHotTrustedTxForwardingTargetsActiveAndTrustedObserverPeers() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.STATIC_MULTI)
                        .peers(List.of(
                                upstreamPeer("peer-a", "peer-a", 3001, 0),
                                upstreamPeer("peer-b", "peer-b", 3002, 1)))
                        .sync(UpstreamSyncConfig.builder()
                                .fanInStart("always")
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(2)
                                .build())
                        .tx(UpstreamTxConfig.builder()
                                .forwarding("all-hot-trusted")
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        List<String> txForwards = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> new RecordingPeerClient(endpoint, point, headerSyncStarts, txForwards));

        try {
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();
            waitForAttempt(headerSyncStarts, "peer-b:3002");
            sync.submitTxBytes("tx-1", new byte[] {1, 2, 3}, TxBodyType.ALONZO);

            waitForAttempt(txForwards, "peer-b:3002");
            assertThat(txForwards).contains("peer-a:3001", "peer-b:3002");
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void txDiffusionSuppressesRepeatedLocalSubmitForwardToSamePeer() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        List<String> txForwards = Collections.synchronizedList(new ArrayList<>());
        DefaultTxDiffusion diffusion = new DefaultTxDiffusion(
                TxDiffusionMode.LOCAL_SUBMIT_ONLY,
                TxCatalog.empty(),
                txCbor -> "unused",
                100,
                1_048_576,
                60_000,
                LoggerFactory.getLogger(SyncSubsystemTest.class));
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> new RecordingPeerClient(endpoint, point, headerSyncStarts, txForwards),
                null,
                () -> diffusion);

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());

            sync.startClientSync();
            waitForAttempt(headerSyncStarts, "localhost:3001");
            sync.submitTxBytes("tx-1", new byte[] {1, 2, 3}, TxBodyType.ALONZO);
            sync.submitTxBytes("tx-1", new byte[] {1, 2, 3}, TxBodyType.ALONZO);

            waitForAttempt(txForwards, "localhost:3001");
            assertThat(txForwards).containsExactly("localhost:3001");
            assertThat(diffusion.stats().outboundForwarded()).isEqualTo(1L);
            assertThat(diffusion.stats().outboundSuppressed()).isEqualTo(1L);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void staticMultiFallsBackWhenPreferredObserverFailsToStart() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.STATIC_MULTI)
                        .peers(List.of(
                                upstreamPeer("peer-a", "peer-a", 3001, 0),
                                upstreamPeer("peer-b", "peer-b", 3002, 1),
                                upstreamPeer("peer-c", "peer-c", 3003, 2)))
                        .sync(UpstreamSyncConfig.builder()
                                .fanInStart("always")
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(2)
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> new RecordingPeerClient(
                        endpoint,
                        point,
                        headerSyncStarts,
                        Collections.synchronizedList(new ArrayList<>()),
                        endpoint.host().equals("peer-b")));

        try {
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();

            waitForAttempt(headerSyncStarts, "peer-c:3003");
            assertThat(sync.upstreamStatus().observerPeerCount()).isEqualTo(1);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }


    private static YanoConfig serverOnlyConfig() {
        return YanoConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42L)
                .serverPort(0)
                .enableServer(false)
                .enableClient(false)
                .useRocksDB(false)
                .fullSyncThreshold(1_800)
                .enablePipelinedSync(true)
                .headerPipelineDepth(10)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .build();
    }

    private static YanoConfig clientConfig() {
        return YanoConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42L)
                .serverPort(0)
                .enableServer(false)
                .enableClient(true)
                .useRocksDB(false)
                .fullSyncThreshold(1_800)
                .enablePipelinedSync(true)
                .headerPipelineDepth(10)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .build();
    }

    private static UpstreamPeerConfig upstreamPeer(String id, String host, int port, int priority) {
        return UpstreamPeerConfig.builder()
                .id(id)
                .host(host)
                .port(port)
                .priority(priority)
                .trust("trusted")
                .build();
    }

    private static EpochParamProvider epochParams(long securityParam, double activeSlotsCoeff) {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public long getSecurityParam() {
                return securityParam;
            }

            @Override
            public double getActiveSlotsCoeff() {
                return activeSlotsCoeff;
            }
        };
    }

    private static void waitForAttempt(List<String> attempts, String expected) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline && !attempts.contains(expected)) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(attempts).contains(expected);
    }

    private static void waitForActivePeer(SyncSubsystem sync, String expected) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline
                && !expected.equals(sync.upstreamStatus().activePeerName())) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(sync.upstreamStatus().activePeerName()).isEqualTo(expected);
    }

    private static final class TestRuntime implements AutoCloseable {
        private final YanoConfig config;
        private final ScheduledExecutorService scheduler;
        private final java.util.function.BooleanSupplier running;
        private final com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory peerClientFactory;
        private final Supplier<EpochParamProvider> epochParamProviderSupplier;
        private final Supplier<TxDiffusion> txDiffusionSupplier;
        private Owned owned;

        private TestRuntime(YanoConfig config,
                            ScheduledExecutorService scheduler,
                            java.util.function.BooleanSupplier running,
                            com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory peerClientFactory) {
            this(config, scheduler, running, peerClientFactory, null);
        }

        private TestRuntime(YanoConfig config,
                            ScheduledExecutorService scheduler,
                            java.util.function.BooleanSupplier running,
                            com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory peerClientFactory,
                            Supplier<EpochParamProvider> epochParamProviderSupplier) {
            this(config, scheduler, running, peerClientFactory, epochParamProviderSupplier, null);
        }

        private TestRuntime(YanoConfig config,
                            ScheduledExecutorService scheduler,
                            java.util.function.BooleanSupplier running,
                            com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory peerClientFactory,
                            Supplier<EpochParamProvider> epochParamProviderSupplier,
                            Supplier<TxDiffusion> txDiffusionSupplier) {
            this.config = config;
            this.scheduler = scheduler;
            this.running = running;
            this.peerClientFactory = peerClientFactory;
            this.epochParamProviderSupplier = epochParamProviderSupplier;
            this.txDiffusionSupplier = txDiffusionSupplier;
        }

        private SyncSubsystem sync(String remoteHost) {
            ChainStorageSubsystem chainStorage = new ChainStorageSubsystem(
                    config,
                    options(),
                    LoggerFactory.getLogger(SyncSubsystemTest.class));
            LedgerStateSubsystem ledgerState = new LedgerStateSubsystem(
                    config,
                    options(),
                    chainStorage.chainState(),
                    new NoopEventBus(),
                    LoggerFactory.getLogger(SyncSubsystemTest.class),
                    null,
                    null,
                    null,
                    null,
                    () -> null,
                    () -> null,
                    () -> null,
                    null);
            ServeSubsystem serve = new ServeSubsystem(
                    0,
                    config.getProtocolMagic(),
                    chainStorage.chainState(),
                    noopTransactionAdmission(),
                    false,
                    LoggerFactory.getLogger(SyncSubsystemTest.class));
            SyncSubsystem sync = new SyncSubsystem(
                    config,
                    chainStorage.chainState(),
                    new NoopEventBus(),
                    scheduler,
                    serve,
                    ledgerState,
                    chainStorage,
                    running,
                    epochParamProviderSupplier != null ? epochParamProviderSupplier : ledgerState::epochParamProvider,
                    ledgerState::currentGenesisBootstrapData,
                    remoteHost,
                    config.getRemotePort(),
                    config.getProtocolMagic(),
                    LoggerFactory.getLogger(SyncSubsystemTest.class),
                    peerClientFactory != null ? peerClientFactory
                            : (endpoint, point) -> new com.bloxbean.cardano.yaci.helper.PeerClient(
                            endpoint.host(), endpoint.port(), endpoint.protocolMagic(), point),
                    txDiffusionSupplier);
            owned = new Owned(sync, ledgerState, serve, chainStorage);
            return sync;
        }

        @Override
        public void close() {
            if (owned != null) {
                owned.close();
                owned = null;
            }
        }
    }

    private static final class RecordingPeerClient extends PeerClient {
        private final PeerEndpoint endpoint;
        private final List<String> headerSyncStarts;
        private final List<String> txForwards;
        private final boolean failOnStart;
        private volatile boolean running;
        private volatile BlockChainDataListener listener;

        private RecordingPeerClient(PeerEndpoint endpoint, Point startPoint, List<String> headerSyncStarts) {
            this(endpoint, startPoint, headerSyncStarts, Collections.synchronizedList(new ArrayList<>()));
        }

        private RecordingPeerClient(PeerEndpoint endpoint,
                                    Point startPoint,
                                    List<String> headerSyncStarts,
                                    List<String> txForwards) {
            this(endpoint, startPoint, headerSyncStarts, txForwards, false);
        }

        private RecordingPeerClient(PeerEndpoint endpoint,
                                    Point startPoint,
                                    List<String> headerSyncStarts,
                                    List<String> txForwards,
                                    boolean failOnStart) {
            super(endpoint.host(), endpoint.port(), endpoint.protocolMagic(), startPoint);
            this.endpoint = endpoint;
            this.headerSyncStarts = headerSyncStarts;
            this.txForwards = txForwards;
            this.failOnStart = failOnStart;
        }

        @Override
        public void connect(BlockChainDataListener listener,
                            com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener txSubmissionListener) {
            this.listener = listener;
        }

        @Override
        public void startHeaderSync(Point startPoint, boolean syncOnly) {
            if (failOnStart) {
                throw new RuntimeException("dial failed");
            }
            running = true;
            headerSyncStarts.add(endpoint.displayName());
        }

        @Override
        public void startSync(Point startPoint) {
            if (failOnStart) {
                throw new RuntimeException("dial failed");
            }
            running = true;
            headerSyncStarts.add(endpoint.displayName());
        }

        @Override
        public void enableTxSubmission() {
        }

        @Override
        public void submitTxBytes(String txHash, byte[] txCbor, TxBodyType txBodyType) {
            txForwards.add(endpoint.displayName());
        }

        @Override
        public Optional<Tip> getLatestTip() {
            return Optional.empty();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void pauseChainSync() {
        }

        @Override
        public void resumeChainSync() {
        }

        @Override
        public void pauseBlockFetch() {
        }

        @Override
        public void resumeBlockFetch() {
        }

        @SuppressWarnings("unused")
        BlockChainDataListener listener() {
            return listener;
        }
    }

    private record Owned(SyncSubsystem sync,
                         LedgerStateSubsystem ledgerState,
                         ServeSubsystem serve,
                         ChainStorageSubsystem chainStorage) implements AutoCloseable {
        @Override
        public void close() {
            sync.close();
            ledgerState.close();
            serve.close();
            chainStorage.closeAfterRuntimeDrain(false);
        }
    }

    private static RuntimeOptions options() {
        return new RuntimeOptions(null, null, Map.of(
                "yano.account-state.enabled", false,
                "yano.account-history.enabled", false));
    }

    private static TransactionAdmission noopTransactionAdmission() {
        return new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                return "tx";
            }

            @Override
            public int mempoolSize() {
                return 0;
            }
        };
    }
}
