package com.bloxbean.cardano.yano.runtime.apply;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.peer.PeerRecoveryReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerApplyProcessorTest {
    private final InMemoryChainState chainState = new InMemoryChainState();
    private final List<PeerRecoveryReason> recoveryReasons = new CopyOnWriteArrayList<>();
    private LedgerApplyProcessor processor;

    @AfterEach
    void tearDown() {
        if (processor != null) {
            processor.close();
        }
    }

    @Test
    void failedBlockDropsLaterBlocksAndRequestsApplyFailedRecovery() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> applied = new ArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> b1 = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            applied.add("B1");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        CompletableFuture<LedgerApplyProcessor.Outcome> b2 = processor.enqueueApplyBlock(generation, "B2", 10, () -> {
            applied.add("B2");
            throw new IllegalStateException("boom");
        });
        CompletableFuture<LedgerApplyProcessor.Outcome> b3 = processor.enqueueApplyBlock(generation, "B3", 10, () -> {
            applied.add("B3");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        CompletableFuture<LedgerApplyProcessor.Outcome> b4 = processor.enqueueApplyBlock(generation, "B4", 10, () -> {
            applied.add("B4");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });

        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(b1));
        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(b2));
        waitUntilDone(b3);
        waitUntilDone(b4);

        assertEquals(List.of("B1", "B2"), applied);
        assertTrue(b3.isCancelled());
        assertTrue(b4.isCancelled());
        assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveryReasons);
    }

    @Test
    void returnedFailedOutcomeFailsGenerationAndRequestsApplyFailedRecovery() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> applied = new ArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> b1 = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            applied.add("B1");
            return LedgerApplyProcessor.Outcome.FAILED;
        });
        CompletableFuture<LedgerApplyProcessor.Outcome> b2 = processor.enqueueApplyBlock(generation, "B2", 10, () -> {
            applied.add("B2");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });

        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(b1));
        waitUntilDone(b2);
        assertTrue(b2.isCancelled());
        assertEquals(List.of("B1"), applied);
        waitUntilRecoveryReasons(1);
        assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveryReasons);
    }

    @Test
    void batchDoneRunsAfterQueuedBlocks() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> order = new ArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> b1 = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            order.add("B1");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        CompletableFuture<LedgerApplyProcessor.Outcome> b2 = processor.enqueueApplyBlock(generation, "B2", 10, () -> {
            order.add("B2");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        CompletableFuture<LedgerApplyProcessor.Outcome> batchDone = processor.enqueueBatchDone(generation,
                () -> order.add("BatchDone"));

        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(b1));
        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(b2));
        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(batchDone));
        assertEquals(List.of("B1", "B2", "BatchDone"), order);
    }

    @Test
    void nonTerminalRollbackRunsInDataQueueOrder() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> b1 = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            order.add("B1");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollback(generation, "rollback", () -> order.add("rollback"));
        CompletableFuture<LedgerApplyProcessor.Outcome> b2 = processor.enqueueApplyBlock(generation, "B2", 10, () -> {
            order.add("B2");
            return LedgerApplyProcessor.Outcome.APPLIED;
        });

        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(b1));
        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(b2));
        assertEquals(List.of("B1", "rollback", "B2"), order);
        assertTrue(processor.isGenerationOpen(generation));
    }

    @Test
    void successfulNonTerminalRollbackRefreshesFailedGenerationRecoveryCursor() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        chainState.storeBlock(hash(9), 9L, 90L, new byte[]{9});
        chainState.storeBlock(hash(10), 10L, 100L, new byte[]{10});
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollback(generation, "rollback", () -> chainState.rollbackTo(90L));
        CompletableFuture<LedgerApplyProcessor.Outcome> failed = processor.enqueueApplyBlock(generation, "B10-again", 10, () -> {
            throw new IllegalStateException("post-rollback apply failure");
        });

        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(failed));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        LedgerApplyProcessor.RecoveryPoint point = recoveryPoint.get(5, TimeUnit.SECONDS);
        assertTip(point.bodyTip(), 90L, 9L);
    }

    @Test
    void acceptedDataRollbackRunsBeforeRecoveryBarrierWhenGenerationCloses() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        chainState.storeBlock(hash(9), 9L, 90L, new byte[]{9});
        chainState.storeBlock(hash(10), 10L, 100L, new byte[]{10});
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollback(generation, "rollback", () -> chainState.rollbackTo(90L));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        LedgerApplyProcessor.RecoveryPoint point = recoveryPoint.get(5, TimeUnit.SECONDS);
        assertTip(point.bodyTip(), 90L, 9L);
    }

    @Test
    void acceptedDataRollbackRunsBeforeDisconnectWhenGenerationCloses() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollback(generation, "rollback", () -> order.add("rollback"));
        CompletableFuture<LedgerApplyProcessor.Outcome> disconnect =
                processor.enqueueDisconnectAndClose(generation, () -> order.add("disconnect"));
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(disconnect));
        assertEquals(List.of("rollback", "disconnect"), order);
    }

    @Test
    void staleBatchDoneAfterGenerationCloseIsCancelled() {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        processor.closeGeneration(generation);

        CompletableFuture<LedgerApplyProcessor.Outcome> staleBatchDone = processor.enqueueBatchDone(generation,
                () -> {
                    throw new AssertionError("stale BatchDone must not run");
                });

        assertTrue(staleBatchDone.isCancelled());
    }

    @Test
    void recoveryBarrierDoesNotCancelAcceptedRollback() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> order.add("rollback"));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertNotNull(recoveryPoint.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("rollback"), order);
    }

    @Test
    void recoveryBarrierFoldsAcceptedRollbackWhenControlQueueIsFull() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 1);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> order.add("rollback"));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertNotNull(recoveryPoint.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("rollback"), order);
    }

    @Test
    void lateRollbackRunsBeforeRecoveryBarrierAfterBarrierClosesDataLane() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        CompletableFuture<LedgerApplyProcessor.Outcome> staleData = processor.enqueueBatchDone(generation,
                () -> {
                    throw new AssertionError("data item after recovery barrier must not run");
                });
        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> {
                    order.add("rollback");
                    chainState.storeBlock(hash(7), 7L, 70L, new byte[]{7});
                    chainState.storeBlockHeader(hash(7), 7L, 70L, new byte[]{7});
                });

        processor.start();

        assertTrue(staleData.isCancelled());
        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        LedgerApplyProcessor.RecoveryPoint point = recoveryPoint.get(5, TimeUnit.SECONDS);
        assertTip(point.bodyTip(), 70L, 7L);
        assertTip(point.headerTip(), 70L, 7L);
        assertEquals(List.of("rollback"), order);
    }

    @Test
    void recoveryBarrierFailsWhenPreviouslyQueuedRollbackFails() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> {
                    throw new IllegalStateException("rollback failed");
                });
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        processor.start();

        waitUntilDone(rollback);
        assertTrue(rollback.isCompletedExceptionally());
        waitUntilDone(recoveryPoint);
        assertTrue(recoveryPoint.isCompletedExceptionally());
    }

    @Test
    void recoveryBarrierFailsWhenLateRollbackBeforeBarrierFails() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> {
                    throw new IllegalStateException("rollback failed");
                });
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(rollback));
        waitUntilDone(recoveryPoint);
        assertTrue(recoveryPoint.isCompletedExceptionally());
    }

    @Test
    void queuedRollbackRunsWhenRunningApplyFailsBeforeRollback() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        CountDownLatch applyStarted = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> running = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            applyStarted.countDown();
            assertTrue(releaseApply.await(5, TimeUnit.SECONDS));
            throw new IllegalStateException("boom");
        });
        processor.start();
        assertTrue(applyStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> order.add("rollback"));
        releaseApply.countDown();

        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(running));
        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertEquals(List.of("rollback"), order);
        waitUntilRecoveryReasons(1);
        assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveryReasons);
    }

    @Test
    void rollbackIsAcceptedAfterGenerationFailed() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> failed = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            throw new IllegalStateException("boom");
        });
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(failed));
        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> order.add("rollback"));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertNotNull(recoveryPoint.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("rollback"), order);
    }

    @Test
    void lateRollbackRunsBeforeQueuedRecoveryBarrierAfterGenerationFailed() throws Exception {
        processor = newProcessor(1, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        processor.enqueueApplyBlock(generation, "B1", 10, () -> LedgerApplyProcessor.Outcome.APPLIED);
        CompletableFuture<LedgerApplyProcessor.Outcome> rejected = processor.enqueueApplyBlock(generation, "B2", 10,
                () -> LedgerApplyProcessor.Outcome.APPLIED);
        waitUntilDone(rejected);

        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> order.add("rollback"));
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertNotNull(recoveryPoint.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("rollback"), order);
    }

    @Test
    void lateRollbackAfterFailedGenerationUpdatesFoldedRecoveryBarrierCursor() throws Exception {
        processor = newProcessor(1, 1024 * 1024, 8);
        chainState.storeBlock(hash(10), 10L, 100L, new byte[]{10});
        long generation = processor.openGeneration();

        processor.enqueueApplyBlock(generation, "queued", 10, () -> LedgerApplyProcessor.Outcome.APPLIED);
        CompletableFuture<LedgerApplyProcessor.Outcome> rejected = processor.enqueueApplyBlock(generation, "rejected", 10,
                () -> LedgerApplyProcessor.Outcome.APPLIED);
        waitUntilDone(rejected);

        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () ->
                        chainState.storeBlock(hash(9), 9L, 90L, new byte[]{9}));
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        LedgerApplyProcessor.RecoveryPoint point = recoveryPoint.get(5, TimeUnit.SECONDS);
        assertTip(point.bodyTip(), 90L, 9L);
    }

    @Test
    void rollbackAfterRecoveryBarrierCompletedIsCancelled() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        chainState.storeBlock(hash(10), 10L, 100L, new byte[]{10});
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        processor.start();

        LedgerApplyProcessor.RecoveryPoint point = recoveryPoint.get(5, TimeUnit.SECONDS);
        assertTip(point.bodyTip(), 100L, 10L);

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "late rollback", () ->
                        chainState.storeBlock(hash(9), 9L, 90L, new byte[]{9}));
        assertTrue(rollback.isCancelled());
        assertTip(chainState.getTip(), 100L, 10L);
    }

    @Test
    void rollbackSupersedesQueuedDisconnectWhenControlQueueIsFull() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 1);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> disconnect =
                processor.enqueueDisconnectAndClose(generation, () -> order.add("disconnect"));
        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> order.add("rollback"));
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(disconnect));
        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertEquals(List.of("disconnect", "rollback"), order);
    }

    @Test
    void disconnectIsSupersededByQueuedRollbackWhenControlQueueIsFull() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 1);
        long generation = processor.openGeneration();
        List<String> order = new CopyOnWriteArrayList<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                processor.enqueueRollbackAndClose(generation, "rollback", () -> order.add("rollback"));
        CompletableFuture<LedgerApplyProcessor.Outcome> disconnect =
                processor.enqueueDisconnectAndClose(generation, () -> order.add("disconnect"));
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(rollback));
        assertEquals(LedgerApplyProcessor.Outcome.COMPLETED, get(disconnect));
        assertEquals(List.of("rollback"), order);
    }

    @Test
    void recoveryBarrierIsAcceptedWhenDataQueueIsFull() throws Exception {
        processor = newProcessor(1, 1024 * 1024, 1);
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.Outcome> queued = processor.enqueueApplyBlock(generation, "B1", 10,
                () -> LedgerApplyProcessor.Outcome.APPLIED);
        CompletableFuture<LedgerApplyProcessor.Outcome> rejected = processor.enqueueApplyBlock(generation, "B2", 10,
                () -> LedgerApplyProcessor.Outcome.APPLIED);

        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        processor.start();

        assertTrue(queued.isCancelled());
        assertTrue(rejected.isCompletedExceptionally());
        assertNotNull(recoveryPoint.get(5, TimeUnit.SECONDS));
        waitUntilRecoveryReasons(1);
        assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveryReasons);
    }

    @Test
    void queueRejectionRequestsRecoveryOnlyAfterRunningApplyReachesSafePoint() throws Exception {
        processor = newProcessor(1, 1024 * 1024, 1);
        long generation = processor.openGeneration();
        CountDownLatch applyStarted = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);

        CompletableFuture<LedgerApplyProcessor.Outcome> running = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            applyStarted.countDown();
            assertTrue(releaseApply.await(5, TimeUnit.SECONDS));
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        processor.start();
        assertTrue(applyStarted.await(5, TimeUnit.SECONDS));

        CompletableFuture<LedgerApplyProcessor.Outcome> queued = processor.enqueueApplyBlock(generation, "B2", 10,
                () -> LedgerApplyProcessor.Outcome.APPLIED);
        CompletableFuture<LedgerApplyProcessor.Outcome> rejected = processor.enqueueApplyBlock(generation, "B3", 10,
                () -> LedgerApplyProcessor.Outcome.APPLIED);

        waitUntilDone(rejected);
        Thread.sleep(Duration.ofMillis(100));
        assertTrue(recoveryReasons.isEmpty(), "recovery must wait for the running apply item");

        releaseApply.countDown();

        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(running));
        waitUntilDone(queued);
        assertTrue(queued.isCancelled());
        waitUntilRecoveryReasons(1);
        assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveryReasons);
    }

    @Test
    void byteLimitRejectionRequestsApplyFailedRecovery() throws Exception {
        processor = newProcessor(10, 8, 1);
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.Outcome> rejected = processor.enqueueApplyBlock(generation, "oversized",
                new byte[9], () -> LedgerApplyProcessor.Outcome.APPLIED);
        processor.start();

        assertTrue(rejected.isCompletedExceptionally());
        waitUntilRecoveryReasons(1);
        assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveryReasons);
    }

    @Test
    void recoveryBarrierWaitsForRunningApplyBeforeReadingTip() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        CountDownLatch applyStarted = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);

        CompletableFuture<LedgerApplyProcessor.Outcome> apply = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            applyStarted.countDown();
            assertTrue(releaseApply.await(5, TimeUnit.SECONDS));
            chainState.storeBlock(hash(1), 1L, 10L, new byte[]{1});
            chainState.storeBlockHeader(hash(1), 1L, 10L, new byte[]{1});
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        processor.start();

        assertTrue(applyStarted.await(5, TimeUnit.SECONDS));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        Thread.sleep(Duration.ofMillis(100));
        assertFalse(recoveryPoint.isDone());

        releaseApply.countDown();

        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(apply));
        LedgerApplyProcessor.RecoveryPoint point = recoveryPoint.get(5, TimeUnit.SECONDS);
        assertTip(point.bodyTip(), 10L, 1L);
        assertTip(point.headerTip(), 10L, 1L);
    }

    @Test
    void recoveryBarrierUsesLastSuccessfulBodyTipAfterFailedApplyAdvancedChainState() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        chainState.storeBlock(hash(1), 1L, 10L, new byte[]{1});
        chainState.storeBlockHeader(hash(2), 2L, 20L, new byte[]{2});
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.Outcome> failed = processor.enqueueApplyBlock(generation, "B2", 10, () -> {
            chainState.storeBlock(hash(2), 2L, 20L, new byte[]{2});
            throw new IllegalStateException("listener failed after body store");
        });
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(failed));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);
        LedgerApplyProcessor.RecoveryPoint point = recoveryPoint.get(5, TimeUnit.SECONDS);

        assertTip(chainState.getTip(), 20L, 2L);
        assertTip(point.bodyTip(), 10L, 1L);
        assertTip(point.headerTip(), 20L, 2L);
    }

    @Test
    void unrecoverableApplyFailureFailsRecoveryBarrierClosed() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        chainState.storeBlock(hash(1), 1L, 10L, new byte[]{1});
        long generation = processor.openGeneration();

        CompletableFuture<LedgerApplyProcessor.Outcome> failed = processor.enqueueApplyBlock(generation, "B2", 10, () -> {
            chainState.storeBlock(hash(2), 2L, 20L, new byte[]{2});
            throw new UnrecoverableApplyException("compensation failed",
                    new IllegalStateException("listener failed"));
        });
        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.FAILED, get(failed));
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> recoveryPoint =
                processor.closeGenerationAndReadRecoveryPoint(generation);

        assertThrows(Exception.class, () -> recoveryPoint.get(5, TimeUnit.SECONDS));
        waitUntilRecoveryReasons(1);
        assertEquals(List.of(PeerRecoveryReason.APPLY_FAILED), recoveryReasons);
        assertEquals(LedgerApplyProcessor.State.DEGRADED, processor.status().state());
    }

    @Test
    void recoveryBarrierCannotBeWaitedFromApplyWorker() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        AtomicReference<CompletableFuture<LedgerApplyProcessor.RecoveryPoint>> barrier = new AtomicReference<>();

        CompletableFuture<LedgerApplyProcessor.Outcome> apply = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            barrier.set(processor.closeGenerationAndReadRecoveryPoint(generation));
            return LedgerApplyProcessor.Outcome.APPLIED;
        });

        processor.start();

        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(apply));
        assertTrue(barrier.get().isCompletedExceptionally());
    }

    @Test
    void closeDuringRunningApplyDoesNotRequestRecovery() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        CountDownLatch applyStarted = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);

        CompletableFuture<LedgerApplyProcessor.Outcome> apply = processor.enqueueApplyBlock(generation, "B1", 10, () -> {
            applyStarted.countDown();
            assertTrue(releaseApply.await(5, TimeUnit.SECONDS));
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        processor.start();
        assertTrue(applyStarted.await(5, TimeUnit.SECONDS));

        assertFalse(processor.closeAndAwait(Duration.ofMillis(50)));
        assertFalse(apply.isDone());
        releaseApply.countDown();

        waitUntilDone(apply);
        assertEquals(LedgerApplyProcessor.Outcome.APPLIED, get(apply));
        assertTrue(processor.closeAndAwait(Duration.ofSeconds(5)));
        assertTrue(recoveryReasons.isEmpty());
        processor = null;
    }

    @Test
    void enqueueAfterCloseIsCancelledAndRecoveryBarrierFails() {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        processor.close();

        CompletableFuture<LedgerApplyProcessor.Outcome> apply = processor.enqueueApplyBlock(generation, "late", 10,
                () -> LedgerApplyProcessor.Outcome.APPLIED);
        CompletableFuture<LedgerApplyProcessor.RecoveryPoint> barrier =
                processor.closeGenerationAndReadRecoveryPoint(generation);

        assertTrue(apply.isCancelled());
        assertTrue(barrier.isCompletedExceptionally());
        processor = null;
    }

    @Test
    void closeClearsQueuedByteAccounting() {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        processor.enqueueApplyBlock(generation, "queued", 100,
                () -> LedgerApplyProcessor.Outcome.APPLIED);

        assertEquals(100, processor.status().queuedDecodedBytes());

        processor.close();

        assertEquals(0, processor.status().queuedDecodedBytes());
        processor = null;
    }

    @Test
    void closeAndAwaitReportsWorkerThatHasNotReachedSafePoint() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        CountDownLatch applyStarted = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);

        processor.enqueueApplyBlock(generation, "nonInterruptible", 10, () -> {
            applyStarted.countDown();
            while (releaseApply.getCount() > 0) {
                try {
                    releaseApply.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a non-interruptible ledger step that has not reached a safe point.
                }
            }
            return LedgerApplyProcessor.Outcome.APPLIED;
        });
        processor.start();
        assertTrue(applyStarted.await(5, TimeUnit.SECONDS));

        assertFalse(processor.closeAndAwait(Duration.ofMillis(50)));

        releaseApply.countDown();
        assertTrue(processor.closeAndAwait(Duration.ofSeconds(5)));
        processor = null;
    }

    @Test
    void forceCloseAndAwaitInterruptsBlockedWorker() throws Exception {
        processor = newProcessor(10, 1024 * 1024, 8);
        long generation = processor.openGeneration();
        CountDownLatch applyStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        processor.enqueueApplyBlock(generation, "interruptible", 10, () -> {
            applyStarted.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(10));
                return LedgerApplyProcessor.Outcome.APPLIED;
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw e;
            }
        });
        processor.start();
        assertTrue(applyStarted.await(5, TimeUnit.SECONDS));

        assertTrue(processor.forceCloseAndAwait(Duration.ofSeconds(5)));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(LedgerApplyProcessor.State.STOPPED, processor.status().state());
        processor = null;
    }

    private LedgerApplyProcessor newProcessor(int maxQueuedItems, long maxQueuedDecodedBytes, int reservedControlSlots) {
        return new LedgerApplyProcessor(
                chainState,
                recoveryReasons::add,
                new LedgerApplyProcessor.Policy(maxQueuedItems, maxQueuedDecodedBytes, reservedControlSlots));
    }

    private LedgerApplyProcessor.Outcome get(CompletableFuture<LedgerApplyProcessor.Outcome> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private void waitUntilDone(CompletableFuture<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(future.isDone(), "future did not complete in time");
    }

    private void waitUntilRecoveryReasons(int expectedSize) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (recoveryReasons.size() < expectedSize && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expectedSize, recoveryReasons.size(), "recovery reason count");
    }

    private byte[] hash(int suffix) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) suffix;
        return bytes;
    }

    private void assertTip(ChainTip tip, long slot, long blockNumber) {
        assertNotNull(tip);
        assertEquals(slot, tip.getSlot());
        assertEquals(blockNumber, tip.getBlockNumber());
    }
}
