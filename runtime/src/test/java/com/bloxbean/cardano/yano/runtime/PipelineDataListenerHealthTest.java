package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yano.api.events.BlockConsensusEvent;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.apply.LedgerApplyProcessor;
import com.bloxbean.cardano.yano.runtime.events.PropagatingEventBus;
import com.bloxbean.cardano.yano.runtime.peer.PeerHealth;
import com.bloxbean.cardano.yano.runtime.peer.PeerRecoveryReason;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionCallbacks;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionStatus;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PipelineDataListenerHealthTest {
    private InMemoryChainState chainState;
    private PeerHealth peerHealth;
    private HeaderSyncManager headerSyncManager;
    private BodyFetchManager bodyFetchManager;
    private PipelineDataListener listener;

    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();
        MockPeerClient peerClient = new MockPeerClient();
        SyncTipContext syncTipContext = new SyncTipContext();
        headerSyncManager = new HeaderSyncManager(peerClient, chainState, 50000, syncTipContext);
        peerHealth = new PeerHealth("relay-1", System.currentTimeMillis());
        bodyFetchManager = new BodyFetchManager(
                peerClient,
                chainState,
                new SimpleEventBus(),
                3,
                5,
                100,
                1000,
                syncTipContext);
        bodyFetchManager.setPeerHealth(peerHealth);
        listener = new PipelineDataListener(headerSyncManager, bodyFetchManager, new NoopCallbacks(), peerHealth);
    }

    @Test
    void rollforwardRecordsHeaderProgress() {
        listener.rollforward(
                new Tip(new Point(1000, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"), 500L),
                createSimpleShelleyHeader(1000L, 500L, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"),
                "header".getBytes());

        PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
        assertEquals(1000L, status.lastHeaderSlot());
        assertEquals(500L, status.lastHeaderBlockNumber());
        assertTrue(status.lastHeaderReceivedAtMillis() > 0);
    }

    @Test
    void staleHeaderAfterLedgerGenerationCloseIsIgnored() {
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, ignored -> {
        });
        long generation = processor.openGeneration();
        processor.start();
        try {
            processor.closeGeneration(generation);
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    bodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);

            asyncListener.rollforward(
                    new Tip(new Point(1000, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"), 500L),
                    createSimpleShelleyHeader(1000L, 500L, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"),
                    "header".getBytes());

            assertNull(chainState.getHeaderTip());
            PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
            assertEquals(-1L, status.lastHeaderSlot());
            assertEquals(-1L, status.lastHeaderBlockNumber());
        } finally {
            processor.close();
        }
    }

    @Test
    void staleIntersectionAfterLedgerGenerationCloseIsIgnored() {
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, ignored -> {
        });
        long generation = processor.openGeneration();
        processor.start();
        AtomicBoolean intersectionFound = new AtomicBoolean(false);
        AtomicBoolean fastTransition = new AtomicBoolean(false);
        try {
            processor.closeGeneration(generation);
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    bodyFetchManager,
                    new NoopCallbacks() {
                        @Override
                        public void onIntersectionFound() {
                            intersectionFound.set(true);
                        }

                        @Override
                        public void maybeFastTransitionToSteadyState(Tip remoteTip) {
                            fastTransition.set(true);
                        }
                    },
                    peerHealth,
                    processor,
                    generation);

            asyncListener.intersactFound(
                    new Tip(new Point(1000, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"), 500L),
                    new Point(900, "bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111"));

            assertFalse(intersectionFound.get());
            assertFalse(fastTransition.get());
        } finally {
            processor.close();
        }
    }

    @Test
    void staleBodyAfterLedgerGenerationCloseDoesNotRefreshHealth() {
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, ignored -> {
        });
        long generation = processor.openGeneration();
        processor.start();
        try {
            processor.closeGeneration(generation);
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    bodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    1001L,
                    501L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
            assertEquals(-1L, status.lastBodyReceivedSlot());
            assertEquals(-1L, status.lastBodyReceivedBlockNumber());
            assertEquals(0L, status.lastBodyReceivedAtMillis());
        } finally {
            processor.close();
        }
    }

    @Test
    void staleDisconnectAfterLedgerGenerationCloseDoesNotInvokePeerDisconnectCallback() {
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, ignored -> {
        });
        long generation = processor.openGeneration();
        processor.start();
        AtomicBoolean disconnected = new AtomicBoolean(false);
        try {
            processor.closeGeneration(generation);
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    bodyFetchManager,
                    new NoopCallbacks() {
                        @Override
                        public void onPeerDisconnected() {
                            disconnected.set(true);
                        }
                    },
                    peerHealth,
                    processor,
                    generation);

            asyncListener.onDisconnect();

            assertFalse(disconnected.get());
            PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
            assertEquals(0L, status.lastDisconnectAtMillis());
        } finally {
            processor.close();
        }
    }

    @Test
    void blockProcessingRecordsBodyReceivedAndAppliedProgress() {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );

        Block block = createTestBlock(
                1001L,
                501L,
                "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        List<Transaction> transactions = Collections.emptyList();

        listener.onBlock(Era.Shelley, block, transactions);

        PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
        assertEquals(1001L, status.lastBodyReceivedSlot());
        assertEquals(501L, status.lastBodyReceivedBlockNumber());
        assertEquals(1001L, status.lastBodyAppliedSlot());
        assertEquals(501L, status.lastBodyAppliedBlockNumber());
        assertTrue(status.lastBodyReceivedAtMillis() > 0);
        assertTrue(status.lastBodyAppliedAtMillis() > 0);

        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        assertEquals(1001L, tip.getSlot());
        assertEquals(501L, tip.getBlockNumber());
    }

    @Test
    void blockProcessingWithLedgerApplyProcessorReturnsBeforeApplyAndEventuallyApplies() throws Exception {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, ignored -> {
        });
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    bodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    1001L,
                    501L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            PeerSessionStatus received = peerHealth.snapshot(System.currentTimeMillis());
            assertEquals(1001L, received.lastBodyReceivedSlot());
            waitForTip(1001L, 501L);

            PeerSessionStatus applied = peerHealth.snapshot(System.currentTimeMillis());
            assertEquals(1001L, applied.lastBodyAppliedSlot());
            assertEquals(501L, applied.lastBodyAppliedBlockNumber());
        } finally {
            processor.close();
        }
    }

    @Test
    void failedBlockApplicationDoesNotRecordBodyAppliedProgress() {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );

        Block block = createTestBlockWithoutCbor(
                1001L,
                501L,
                "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

        assertThrows(RuntimeException.class,
                () -> listener.onBlock(Era.Shelley, block, Collections.emptyList()));

        PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
        assertEquals(1001L, status.lastBodyReceivedSlot());
        assertEquals(501L, status.lastBodyReceivedBlockNumber());
        assertEquals(-1L, status.lastBodyAppliedSlot());
        assertEquals(-1L, status.lastBodyAppliedBlockNumber());
        assertTrue(status.lastBodyReceivedAtMillis() > 0);
        assertEquals(0L, status.lastBodyAppliedAtMillis());
    }

    @Test
    void failedAsyncBlockRequestsApplyFailedRecovery() throws Exception {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    bodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlockWithoutCbor(
                    1001L,
                    501L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            waitForRecoveries(recoveries, 1);
            assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveries);
            PeerSessionStatus status = peerHealth.snapshot(System.currentTimeMillis());
            assertEquals(1001L, status.lastBodyReceivedSlot());
            assertEquals(-1L, status.lastBodyAppliedSlot());
        } finally {
            processor.close();
        }
    }

    @Test
    void consensusRejectedAsyncBlockRequestsApplyFailedRecovery() throws Exception {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );
        SimpleEventBus rejectingBus = new SimpleEventBus();
        rejectingBus.subscribe(BlockConsensusEvent.class,
                ctx -> ctx.event().reject("test", "reject block"),
                SubscriptionOptions.builder().build());
        BodyFetchManager rejectingBodyFetchManager = new BodyFetchManager(
                new MockPeerClient(),
                chainState,
                rejectingBus,
                3,
                5,
                100,
                1000,
                new SyncTipContext());
        rejectingBodyFetchManager.setPeerHealth(peerHealth);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    rejectingBodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    1001L,
                    501L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            waitForRecoveries(recoveries, 1);
            assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveries);
            ChainTip tip = chainState.getTip();
            assertNotNull(tip);
            assertEquals(1000L, tip.getSlot());
            assertEquals(500L, tip.getBlockNumber());
        } finally {
            processor.close();
            rejectingBus.close();
        }
    }

    @Test
    void continuityViolationAsyncBlockRequestsApplyFailedRecovery() throws Exception {
        InMemoryChainState continuityChainState = new InMemoryChainState() {
            @Override
            public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {
                if (blockNumber == 501L) {
                    throw new RuntimeException("CONTINUITY VIOLATION test failure");
                }
                super.storeBlock(blockHash, blockNumber, slot, block);
            }
        };
        continuityChainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes());
        BodyFetchManager continuityBodyFetchManager = new BodyFetchManager(
                new MockPeerClient(),
                continuityChainState,
                new SimpleEventBus(),
                3,
                5,
                100,
                1000,
                new SyncTipContext());
        continuityBodyFetchManager.setPeerHealth(peerHealth);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(continuityChainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    continuityBodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    1001L,
                    501L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            waitForRecoveries(recoveries, 1);
            assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveries);
            ChainTip tip = continuityChainState.getTip();
            assertNotNull(tip);
            assertEquals(1000L, tip.getSlot());
            assertEquals(500L, tip.getBlockNumber());
        } finally {
            processor.close();
        }
    }

    @Test
    void parsingErrorWithLedgerApplyProcessorRequestsApplyFailedRecovery() throws Exception {
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    bodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);

            asyncListener.onParsingError(new BlockParseRuntimeException(
                    501L,
                    new byte[]{1, 2, 3},
                    "parse failure",
                    new IllegalStateException("bad cbor")));

            waitForRecoveries(recoveries, 1);
            assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveries);
        } finally {
            processor.close();
        }
    }

    @Test
    void blockAppliedListenerFailureRequestsRecoveryAndRecoveryPointStaysAtPreviousBodyTip() throws Exception {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );
        PropagatingEventBus propagatingBus = new PropagatingEventBus();
        AtomicInteger derivedSideEffect = new AtomicInteger();
        propagatingBus.subscribe(BlockAppliedEvent.class,
                ctx -> derivedSideEffect.incrementAndGet(),
                SubscriptionOptions.builder().priority(90).build());
        propagatingBus.subscribe(RollbackEvent.class,
                ctx -> derivedSideEffect.decrementAndGet(),
                SubscriptionOptions.builder().priority(90).build());
        propagatingBus.subscribe(BlockAppliedEvent.class,
                ctx -> {
                    throw new IllegalStateException("derived store failed");
                },
                SubscriptionOptions.builder().build());
        BodyFetchManager failingBodyFetchManager = new BodyFetchManager(
                new MockPeerClient(),
                chainState,
                propagatingBus,
                3,
                5,
                100,
                1000,
                new SyncTipContext());
        failingBodyFetchManager.setPeerHealth(peerHealth);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    failingBodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    1001L,
                    501L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            waitForRecoveries(recoveries, 1);
            assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveries);
            ChainTip storedTip = chainState.getTip();
            assertNotNull(storedTip);
            assertEquals(1000L, storedTip.getSlot());
            assertEquals(500L, storedTip.getBlockNumber());
            assertEquals(0, derivedSideEffect.get());

            LedgerApplyProcessor.RecoveryPoint point =
                    processor.closeGenerationAndReadRecoveryPoint(generation).get(5, TimeUnit.SECONDS);
            assertNotNull(point.bodyTip());
            assertEquals(1000L, point.bodyTip().getSlot());
            assertEquals(500L, point.bodyTip().getBlockNumber());
        } finally {
            processor.close();
            propagatingBus.close();
        }
    }

    @Test
    void compensatingRollbackFailureFailsRecoveryBarrierClosed() throws Exception {
        chainState.storeBlock(
                hexToBytes("0000000000000000000000000000000000000000000000000000000000000ac0"),
                500L,
                1000L,
                "00".getBytes()
        );
        PropagatingEventBus propagatingBus = new PropagatingEventBus();
        propagatingBus.subscribe(BlockAppliedEvent.class,
                ctx -> {
                    throw new IllegalStateException("derived store failed");
                },
                SubscriptionOptions.builder().build());
        propagatingBus.subscribe(RollbackEvent.class,
                ctx -> {
                    throw new IllegalStateException("rollback listener failed");
                },
                SubscriptionOptions.builder().build());
        BodyFetchManager failingBodyFetchManager = new BodyFetchManager(
                new MockPeerClient(),
                chainState,
                propagatingBus,
                3,
                5,
                100,
                1000,
                new SyncTipContext());
        failingBodyFetchManager.setPeerHealth(peerHealth);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    failingBodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    1001L,
                    501L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            waitForRecoveries(recoveries, 1);
            assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveries);
            assertEquals(1000L, chainState.getTip().getSlot());

            var recoveryPoint = processor.closeGenerationAndReadRecoveryPoint(generation);
            assertThrows(Exception.class, () -> recoveryPoint.get(5, TimeUnit.SECONDS));
            assertEquals(LedgerApplyProcessor.State.DEGRADED, processor.status().state());
        } finally {
            processor.close();
            propagatingBus.close();
        }
    }

    @Test
    void firstBlockPostStoreFailureRollsBackToOrigin() throws Exception {
        PropagatingEventBus propagatingBus = new PropagatingEventBus();
        propagatingBus.subscribe(BlockAppliedEvent.class,
                ctx -> {
                    throw new IllegalStateException("derived store failed");
                },
                SubscriptionOptions.builder().build());
        BodyFetchManager failingBodyFetchManager = new BodyFetchManager(
                new MockPeerClient(),
                chainState,
                propagatingBus,
                3,
                5,
                100,
                1000,
                new SyncTipContext());
        failingBodyFetchManager.setPeerHealth(peerHealth);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    failingBodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    1001L,
                    1L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            waitForRecoveries(recoveries, 1);
            assertNull(chainState.getTip());
            assertNull(chainState.getHeaderTip());

            LedgerApplyProcessor.RecoveryPoint point =
                    processor.closeGenerationAndReadRecoveryPoint(generation).get(5, TimeUnit.SECONDS);
            assertNull(point.bodyTip());
        } finally {
            processor.close();
            propagatingBus.close();
        }
    }

    @Test
    void slotZeroFirstBlockFailurePublishesOriginRollbackTarget() throws Exception {
        PropagatingEventBus propagatingBus = new PropagatingEventBus();
        List<Long> rollbackTargets = new CopyOnWriteArrayList<>();
        propagatingBus.subscribe(BlockAppliedEvent.class,
                ctx -> {
                    // A prior derived listener succeeded before a later listener failed.
                },
                SubscriptionOptions.builder().build());
        propagatingBus.subscribe(BlockAppliedEvent.class,
                ctx -> {
                    throw new IllegalStateException("later derived store failed");
                },
                SubscriptionOptions.builder().build());
        propagatingBus.subscribe(RollbackEvent.class,
                ctx -> rollbackTargets.add(ctx.event().target().getSlot()),
                SubscriptionOptions.builder().build());
        BodyFetchManager failingBodyFetchManager = new BodyFetchManager(
                new MockPeerClient(),
                chainState,
                propagatingBus,
                3,
                5,
                100,
                1000,
                new SyncTipContext());
        failingBodyFetchManager.setPeerHealth(peerHealth);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        LedgerApplyProcessor processor = new LedgerApplyProcessor(chainState, recoveries::add);
        long generation = processor.openGeneration();
        processor.start();
        try {
            PipelineDataListener asyncListener = new PipelineDataListener(
                    headerSyncManager,
                    failingBodyFetchManager,
                    new NoopCallbacks(),
                    peerHealth,
                    processor,
                    generation);
            Block block = createTestBlock(
                    0L,
                    1L,
                    "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            asyncListener.onBlock(Era.Shelley, block, Collections.emptyList());

            waitForRecoveries(recoveries, 1);
            assertNull(chainState.getTip());
            assertNull(chainState.getHeaderTip());
            assertEquals(List.of(-1L), rollbackTargets);
        } finally {
            processor.close();
            propagatingBus.close();
        }
    }

    @Test
    void batchAndDisconnectUpdateHealth() {
        listener.batchStarted();

        PeerSessionStatus started = peerHealth.snapshot(System.currentTimeMillis());
        assertTrue(started.bodyFetchInProgress());
        assertTrue(started.bodyFetchStartedAtMillis() > 0);

        listener.noBlockFound(new Point(10, "from"), new Point(11, "to"));
        PeerSessionStatus noBlockFound = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(noBlockFound.bodyFetchInProgress());

        listener.batchStarted();
        listener.batchDone();
        PeerSessionStatus done = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(done.bodyFetchInProgress());

        listener.batchStarted();
        listener.onRollback(new Point(12, "rollback"));
        PeerSessionStatus rolledBack = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(rolledBack.bodyFetchInProgress());

        listener.batchStarted();
        listener.onDisconnect();
        PeerSessionStatus disconnected = peerHealth.snapshot(System.currentTimeMillis());
        assertFalse(disconnected.bodyFetchInProgress());
        assertTrue(disconnected.lastDisconnectAtMillis() > 0);
    }

    @Test
    void disconnectInvokesPeerDisconnectCallback() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        MockPeerClient peerClient = new MockPeerClient();
        SyncTipContext syncTipContext = new SyncTipContext();
        HeaderSyncManager headerSyncManager = new HeaderSyncManager(peerClient, chainState, 50000, syncTipContext);
        BodyFetchManager bodyFetchManager = new BodyFetchManager(
                peerClient,
                chainState,
                new SimpleEventBus(),
                3,
                5,
                100,
                1000,
                syncTipContext);
        bodyFetchManager.setPeerHealth(peerHealth);
        PipelineDataListener disconnectListener = new PipelineDataListener(
                headerSyncManager,
                bodyFetchManager,
                new NoopCallbacks() {
                    @Override
                    public void onPeerDisconnected() {
                        callbackInvoked.set(true);
                    }
                },
                peerHealth);

        disconnectListener.onDisconnect();

        assertTrue(callbackInvoked.get());
    }

    private BlockHeader createSimpleShelleyHeader(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(blockNumber)
                .blockHash(hash)
                .build();

        return BlockHeader.builder()
                .headerBody(headerBody)
                .build();
    }

    private Block createTestBlock(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(blockNumber)
                .blockHash(hash)
                .build();

        BlockHeader header = BlockHeader.builder()
                .headerBody(headerBody)
                .build();

        return Block.builder()
                .header(header)
                .cbor("deadbeefabcd1234")
                .build();
    }

    private Block createTestBlockWithoutCbor(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(blockNumber)
                .blockHash(hash)
                .build();

        BlockHeader header = BlockHeader.builder()
                .headerBody(headerBody)
                .build();

        return Block.builder()
                .header(header)
                .build();
    }

    private byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private void waitForTip(long slot, long blockNumber) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ChainTip tip = chainState.getTip();
            if (tip != null && tip.getSlot() == slot && tip.getBlockNumber() == blockNumber) {
                return;
            }
            Thread.sleep(10);
        }
        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        assertEquals(slot, tip.getSlot());
        assertEquals(blockNumber, tip.getBlockNumber());
    }

    private void waitForRecoveries(List<PeerRecoveryReason> recoveries, int expectedCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (recoveries.size() < expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expectedCount, recoveries.size());
    }

    private static class NoopCallbacks implements PeerSessionCallbacks {
        @Override
        public void resumeBodyFetchOnHeaderFlow() {
        }

        @Override
        public void updateSyncProgress() {
        }

        @Override
        public void notifyServerNewBlockStored() {
        }

        @Override
        public void onIntersectionFound() {
        }

        @Override
        public void maybeFastTransitionToSteadyState(Tip remoteTip) {
        }

        @Override
        public void handleChainSyncRollback(Point point) {
        }
    }

    private static class MockPeerClient extends PeerClient {
        MockPeerClient() {
            super("mock-host", 3001, 1, null);
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
