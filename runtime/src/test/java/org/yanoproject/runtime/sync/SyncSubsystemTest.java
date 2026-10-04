package org.yanoproject.runtime.sync;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.SyncPhase;
import org.yanoproject.api.config.ChainSelectionConfig;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.UpstreamConfig;
import org.yanoproject.api.config.UpstreamDiscoveryConfig;
import org.yanoproject.api.config.UpstreamFailoverConfig;
import org.yanoproject.api.config.UpstreamGovernorConfig;
import org.yanoproject.api.config.UpstreamPeerConfig;
import org.yanoproject.api.config.UpstreamPreset;
import org.yanoproject.api.config.UpstreamSyncConfig;
import org.yanoproject.api.config.UpstreamTxConfig;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.events.RollbackEvent;
import org.yanoproject.runtime.kernel.SubsystemHealth;
import org.yanoproject.runtime.ledger.LedgerStateSubsystem;
import org.yanoproject.p2p.peer.PeerEndpoint;
import org.yanoproject.p2p.peer.PeerRecoveryReason;
import org.yanoproject.runtime.server.ServeSubsystem;
import org.yanoproject.runtime.storage.ChainStorageSubsystem;
import org.yanoproject.runtime.tx.TransactionAdmission;
import org.yanoproject.p2p.tx.diffusion.DefaultTxDiffusion;
import org.yanoproject.p2p.tx.diffusion.TxCatalog;
import org.yanoproject.p2p.tx.diffusion.TxDiffusion;
import org.yanoproject.p2p.tx.diffusion.TxDiffusionMode;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class SyncSubsystemTest {

    @Test
    void reconnectAheadOfBodySeedsEpochFromDurableBodyTip() {
        var bodyTip = new ChainTip(23_172_445L, new byte[32], 726_489L);

        assertThat(SyncSubsystem.durableEpochResumeSlot(24_477_664L, bodyTip))
                .isEqualTo(23_172_445L);
        assertThat(SyncSubsystem.durableEpochResumeSlot(24_477_664L, null))
                .isEqualTo(24_477_664L);
    }

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
                "yano.account-state.enabled", false));
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
            assertThat(sync.upstreamStatus().configuredPeerCount()).isZero();
            assertThat(sync.upstreamStatus().activePeerName()).isNull();
            assertThat(sync.peerGovernorSnapshot().peerInfos())
                    .noneMatch(peer -> peer.descriptor() != null
                            && peer.descriptor().source()
                            == org.yanoproject.p2p.governor.PeerSource.STATIC_UPSTREAM);
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
        YanoConfig config = clientConfig();
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
    void pipelinedSyncStepsBackFromAnOrphanedTipUntilTheUpstreamFindsAnIntersection() {
        assertStepsBackFromOrphanedTip(true);
    }

    @Test
    void sequentialSyncStepsBackFromAnOrphanedTipUntilTheUpstreamFindsAnIntersection() {
        assertStepsBackFromOrphanedTip(false);
    }

    private static void assertStepsBackFromOrphanedTip(boolean pipelined) {
        YanoConfig config = clientConfig().toBuilder().enablePipelinedSync(pipelined).build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());
            storeChain(runtime, 1, 20);

            sync.startClientSync();
            rejectIntersection(clients, 1);
            rejectIntersection(clients, 2);
            rejectIntersection(clients, 3);

            waitForClients(clients, 4);
            assertThat(startSlots(clients)).containsExactly(200L, 190L, 180L, 160L);

            // Found at block 16: the search resets, so a later rejection starts next to the tip again
            clients.get(3).listener().intersactFound(new Tip(clients.get(3).startPoint, 16), clients.get(3).startPoint);
            rejectIntersection(clients, 4);
            waitForClients(clients, 5);
            assertThat(clients.get(4).startPoint.getSlot()).isEqualTo(190L);
            assertThat(sync.peerRecoverySnapshot().terminal()).isFalse();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void intersectionSearchStopsAtTheSecurityParameterWithAnExplicitTerminalFailure() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients),
                () -> epochParams(3L, 1.0D));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());
            storeChain(runtime, 1, 20);

            sync.startClientSync();
            for (int attempt = 1; attempt <= 4; attempt++) {
                rejectIntersection(clients, attempt);
            }

            waitForTerminal(sync);
            // Doubling would reach 4 blocks back, deeper than k = 3; block 17 is offered once instead
            assertThat(startSlots(clients)).containsExactly(200L, 190L, 180L, 170L);
            waitUntil(() -> !clients.get(3).isRunning());
            assertThat(clients.get(3).isRunning()).as("the rejected session is closed").isFalse();
            assertThat(sync.isSyncing()).isFalse();
            assertThat(sync.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);
            assertThat(sync.health().message()).contains("NO_INTERSECTION_WITHIN_RECOVERABLE_HISTORY");
            assertThat(sync.currentPeerSessionStatus().lastRecoveryReason())
                    .isEqualTo(PeerRecoveryReason.INTERSECTION_FAILED);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void intersectionSearchNeverOffersAPointBelowTheRollbackFloor() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());
            storeChain(runtime, 1, 20);
            sync.setRollbackFloorSlot(() -> 170L); // e.g. the UTXO store kept deltas back to block 17 only

            sync.startClientSync();
            for (int attempt = 1; attempt <= 4; attempt++) {
                rejectIntersection(clients, attempt);
            }

            waitForTerminal(sync);
            // Doubling would reach block 16, below the floor; the oldest recoverable block 17 is offered once
            assertThat(startSlots(clients)).containsExactly(200L, 190L, 180L, 170L);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void pipelinedSearchStepsBackThroughHeaderOnlyBlocksAheadOfTheBodyTip() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());
            storeChain(runtime, 1, 10);
            for (long number = 11; number <= 12; number++) { // headers only: bodies not fetched yet
                runtime.owned.chainStorage().chainState().storeBlockHeader(blockHash(number), number, number * 10,
                        new byte[]{1});
            }

            sync.startClientSync();
            rejectIntersection(clients, 1);
            rejectIntersection(clients, 2);

            waitForClients(clients, 3);
            assertThat(startSlots(clients)).as("down to the body tip, block 10").containsExactly(120L, 110L, 100L);
            assertThat(sync.peerRecoverySnapshot().terminal()).isFalse();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void intersectionSearchOffersTheOldestIndexedAncestorBeforeStopping() {
        YanoConfig config = clientConfig().toBuilder().enablePipelinedSync(false).build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());
            storeChain(runtime, 15, 20); // the local index starts at block 15

            sync.startClientSync();
            for (int attempt = 1; attempt <= 5; attempt++) {
                rejectIntersection(clients, attempt);
            }

            waitForTerminal(sync);
            assertThat(startSlots(clients)).containsExactly(200L, 190L, 180L, 160L, 150L);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void rollbackToASteppedBackIntersectionIsARealReorg() {
        YanoConfig config = clientConfig().toBuilder().enablePipelinedSync(false).build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        List<RollbackEvent> rollbacks = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());
            runtime.events.subscribe(RollbackEvent.class, ctx -> rollbacks.add(ctx.event()),
                    SubscriptionOptions.builder().build());
            storeChain(runtime, 1, 20);

            sync.startClientSync();
            rejectIntersection(clients, 1);
            waitForClients(clients, 2);
            Point ancestor = clients.get(1).startPoint;
            clients.get(1).listener().intersactFound(new Tip(ancestor, 19), ancestor);
            clients.get(1).listener().intersactFound(new Tip(ancestor, 19), ancestor); // yaci may repeat it
            sync.handleChainSyncRollback(ancestor);

            // Still in INTERSECT_PHASE, yet block 20 is gone: consumers must see the rollback
            assertThat(rollbacks).singleElement().satisfies(event -> assertThat(event.realReorg()).isTrue());
            assertThat(runtime.owned.chainStorage().chainState().getTip().getBlockNumber()).isEqualTo(19L);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void intersectionSearchKeepsItsProgressAcrossAReconnect() {
        YanoConfig config = clientConfig().toBuilder().enablePipelinedSync(false).build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients));

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());
            storeChain(runtime, 1, 20);

            sync.startClientSync();
            rejectIntersection(clients, 1);
            waitForClients(clients, 2);
            sync.requestPeerRecovery(PeerRecoveryReason.DISCONNECT_STALE); // a timeout, not a rejection

            waitForClients(clients, 3);
            assertThat(startSlots(clients)).containsExactly(200L, 190L, 190L);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void repeatedIntersectNotFoundInOneSessionIsHandledOnce() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger submissions = new AtomicInteger();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, null);
        runtime.peerRecoveryExecutor.shutdownNow();
        runtime.peerRecoveryExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                submissions.incrementAndGet();
                super.execute(command);
            }
        };

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());

            sync.onIntersectionNotFound();
            sync.onIntersectionNotFound();

            assertThat(submissions).hasValue(1);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void intersectionSearchTriesTheNextUpstreamBeforeStopping() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.TRUSTED_FAILOVER)
                        .peers(List.of(
                                upstreamPeer("a", "relay-a", 3001, 0),
                                upstreamPeer("b", "relay-b", 3002, 1)))
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<RecordingPeerClient> clients = Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true, recordingClients(clients));

        try {
            SyncSubsystem sync = runtime.sync(null);
            storeChain(runtime, 1, 1); // nothing older than the tip to offer

            sync.startClientSync();
            rejectIntersection(clients, 1);
            rejectIntersection(clients, 2);

            waitForTerminal(sync);
            assertThat(clients).extracting(client -> client.endpoint.displayName())
                    .containsExactly("relay-a:3001", "relay-b:3002");
            assertThat(startSlots(clients)).containsExactly(10L, 10L);
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
    void allHotTxForwardingTargetsUntrustedObserverPeers() {
        YanoConfig config = clientConfig().toBuilder()
                .remoteHost(null)
                .remotePort(0)
                .upstream(UpstreamConfig.builder()
                        .mode(UpstreamPreset.STATIC_MULTI)
                        .peers(List.of(
                                upstreamPeer("peer-a", "peer-a", 3001, 0),
                                untrustedUpstreamPeer("peer-b", "peer-b", 3002, 1)))
                        .sync(UpstreamSyncConfig.builder()
                                .fanInStart("always")
                                .build())
                        .governor(UpstreamGovernorConfig.builder()
                                .targetHot(2)
                                .build())
                        .tx(UpstreamTxConfig.builder()
                                .forwarding("all-hot")
                                .build())
                        .build())
                .build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<String> headerSyncStarts = Collections.synchronizedList(new ArrayList<>());
        List<String> txForwards = Collections.synchronizedList(new ArrayList<>());
        DefaultTxDiffusion diffusion = new DefaultTxDiffusion(
                TxDiffusionMode.ALL_HOT,
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
            SyncSubsystem sync = runtime.sync(null);

            sync.startClientSync();
            waitForAttempt(headerSyncStarts, "peer-b:3002");
            sync.submitTxBytes("tx-1", new byte[] {1, 2, 3}, TxBodyType.ALONZO);

            waitForAttempt(txForwards, "peer-b:3002");
            assertThat(txForwards).contains("peer-a:3001", "peer-b:3002");
            assertThat(diffusion.stats().outboundForwarded()).isEqualTo(2L);
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

    private static UpstreamPeerConfig untrustedUpstreamPeer(String id, String host, int port, int priority) {
        return upstreamPeer(id, host, port, priority).toBuilder()
                .trust("untrusted")
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

    private static org.yanoproject.p2p.peer.PeerClientFactory recordingClients(List<RecordingPeerClient> clients) {
        return (endpoint, point) -> {
            RecordingPeerClient client = new RecordingPeerClient(endpoint, point,
                    Collections.synchronizedList(new ArrayList<>()));
            clients.add(client);
            return client;
        };
    }

    /** Blocks first..last at slot 10 * number. */
    private static void storeChain(TestRuntime runtime, long first, long last) {
        for (long number = first; number <= last; number++) {
            runtime.owned.chainStorage().chainState().storeBlock(blockHash(number), number, number * 10,
                    new byte[]{1});
        }
    }

    private static byte[] blockHash(long number) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, (byte) number);
        return hash;
    }

    /** The upstream answers the n-th session's FindIntersect with IntersectNotFound, twice as yaci may. */
    private static void rejectIntersection(List<RecordingPeerClient> clients, int session) {
        waitForClients(clients, session);
        RecordingPeerClient client = clients.get(session - 1);
        client.listener().intersactNotFound(new Tip(new Point(500, "ff"), 50));
        client.listener().intersactNotFound(new Tip(new Point(500, "ff"), 50));
    }

    private static List<Long> startSlots(List<RecordingPeerClient> clients) {
        synchronized (clients) {
            return clients.stream().map(client -> client.startPoint.getSlot()).toList();
        }
    }

    private static void waitForClients(List<RecordingPeerClient> clients, int count) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline
                && (clients.size() < count || clients.get(count - 1).listener() == null)) {
            sleepBriefly();
        }
        assertThat(clients).hasSizeGreaterThanOrEqualTo(count);
    }

    /** Waits for sync to stop, the last state change of the terminal outcome. */
    private static void waitForTerminal(SyncSubsystem sync) {
        waitUntil(() -> !sync.isSyncing());
        assertThat(sync.peerRecoverySnapshot().terminal()).isTrue();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        private final org.yanoproject.p2p.peer.PeerClientFactory peerClientFactory;
        private final Supplier<EpochParamProvider> epochParamProviderSupplier;
        private final Supplier<TxDiffusion> txDiffusionSupplier;
        private final SimpleEventBus events = new SimpleEventBus();
        private ExecutorService peerRecoveryExecutor = Executors.newSingleThreadExecutor();
        private Owned owned;

        private TestRuntime(YanoConfig config,
                            ScheduledExecutorService scheduler,
                            java.util.function.BooleanSupplier running,
                            org.yanoproject.p2p.peer.PeerClientFactory peerClientFactory) {
            this(config, scheduler, running, peerClientFactory, null);
        }

        private TestRuntime(YanoConfig config,
                            ScheduledExecutorService scheduler,
                            java.util.function.BooleanSupplier running,
                            org.yanoproject.p2p.peer.PeerClientFactory peerClientFactory,
                            Supplier<EpochParamProvider> epochParamProviderSupplier) {
            this(config, scheduler, running, peerClientFactory, epochParamProviderSupplier, null);
        }

        private TestRuntime(YanoConfig config,
                            ScheduledExecutorService scheduler,
                            java.util.function.BooleanSupplier running,
                            org.yanoproject.p2p.peer.PeerClientFactory peerClientFactory,
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
                    events,
                    scheduler,
                    peerRecoveryExecutor,
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
                    null,
                    txDiffusionSupplier,
                    peerClientFactory != null ? peerClientFactory
                            : (endpoint, point) -> new com.bloxbean.cardano.yaci.helper.PeerClient(
                            endpoint.host(), endpoint.port(), endpoint.protocolMagic(), point));
            owned = new Owned(sync, ledgerState, serve, chainStorage);
            return sync;
        }

        @Override
        public void close() {
            if (owned != null) {
                owned.close();
                owned = null;
            }
            peerRecoveryExecutor.shutdownNow();
        }
    }

    private static final class RecordingPeerClient extends PeerClient {
        private final PeerEndpoint endpoint;
        private final Point startPoint;
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
            this.startPoint = startPoint;
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
                "yano.account-state.enabled", false));
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
