package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yano.runtime.apply.LedgerApplyProcessor;
import com.bloxbean.cardano.yano.runtime.peer.PeerHealth;
import com.bloxbean.cardano.yano.runtime.peer.PeerRecoveryReason;
import com.bloxbean.cardano.yano.runtime.peer.PeerSessionCallbacks;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PipelineDataListener is an adapter that implements BlockChainDataListener
 * and delegates events to the appropriate pipeline managers:
 * - HeaderSyncManager for ChainSync events (headers)
 * - BodyFetchManager for BlockFetch events (bodies)
 * - Yano for rollback coordination
 *
 * This allows the pipeline architecture to work with the existing
 * PeerClient.connect() method without modifications.
 */
@Slf4j
public class PipelineDataListener implements BlockChainDataListener {
    private static final long SLOW_BODY_CALLBACK_WARN_MS =
            positiveLongProperty("yano.pipeline.slowBodyCallbackWarnMs", 1_000L);

    private final HeaderSyncManager headerSyncManager;
    private final BodyFetchManager bodyFetchManager;
    private final PeerSessionCallbacks callbacks;
    private final PeerHealth peerHealth;
    private final LedgerApplyProcessor ledgerApplyProcessor;
    private final long ledgerGeneration;
    private final AtomicInteger activeRollbackCallbacks = new AtomicInteger();
    private final Object rollbackCallbackDrainMonitor = new Object();

    /**
     * Create a new PipelineDataListener
     *
     * @param headerSyncManager Manager for header synchronization
     * @param bodyFetchManager Manager for body fetching
     * @param callbacks Runtime callbacks for rollback and sync progress coordination
     */
    public PipelineDataListener(HeaderSyncManager headerSyncManager,
                               BodyFetchManager bodyFetchManager,
                               PeerSessionCallbacks callbacks) {
        this(headerSyncManager, bodyFetchManager, callbacks, null);
    }

    public PipelineDataListener(HeaderSyncManager headerSyncManager,
                               BodyFetchManager bodyFetchManager,
                               PeerSessionCallbacks callbacks,
                               PeerHealth peerHealth) {
        this(headerSyncManager, bodyFetchManager, callbacks, peerHealth, null, 0);
    }

    public PipelineDataListener(HeaderSyncManager headerSyncManager,
                               BodyFetchManager bodyFetchManager,
                               PeerSessionCallbacks callbacks,
                               PeerHealth peerHealth,
                               LedgerApplyProcessor ledgerApplyProcessor,
                               long ledgerGeneration) {
        this.headerSyncManager = headerSyncManager;
        this.bodyFetchManager = bodyFetchManager;
        this.callbacks = callbacks;
        this.peerHealth = peerHealth;
        this.ledgerApplyProcessor = ledgerApplyProcessor;
        this.ledgerGeneration = ledgerGeneration;

        log.info("PipelineDataListener initialized for parallel header/body processing");
    }

    // ================================================================
    // ChainSync Events - Delegate to HeaderSyncManager
    // ================================================================

    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        if (!acceptsCurrentGeneration("Shelley header rollforward")) {
            return;
        }
        // Delegate header processing to HeaderSyncManager
        headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes);
        if (peerHealth != null && blockHeader != null && blockHeader.getHeaderBody() != null) {
            peerHealth.recordHeaderProgress(
                    blockHeader.getHeaderBody().getSlot(),
                    blockHeader.getHeaderBody().getBlockNumber(),
                    System.currentTimeMillis());
        }

        //TODO remove this log
//        log.info("Rollforward to header: {} at slot: {}", blockHeader.getHeaderBody().getBlockNumber(), blockHeader.getHeaderBody().getSlot());

        // Resume BodyFetchManager if paused and headers are flowing after intersection
        callbacks.resumeBodyFetchOnHeaderFlow();
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        if (!acceptsCurrentGeneration("Byron header rollforward")) {
            return;
        }
        // Delegate Byron header processing to HeaderSyncManager
        headerSyncManager.rollforwardByronEra(tip, byronBlockHead, originalHeaderBytes);
        if (peerHealth != null && byronBlockHead != null && byronBlockHead.getConsensusData() != null) {
            peerHealth.recordHeaderProgress(
                    byronBlockHead.getConsensusData().getAbsoluteSlot(),
                    byronBlockHead.getConsensusData().getDifficulty().longValue(),
                    System.currentTimeMillis());
        }

        // Resume BodyFetchManager if paused and headers are flowing after intersection
        callbacks.resumeBodyFetchOnHeaderFlow();
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        if (!acceptsCurrentGeneration("Byron EB header rollforward")) {
            return;
        }
        // Delegate Byron EB header processing to HeaderSyncManager
        headerSyncManager.rollforwardByronEra(tip, byronEbHead, originalHeaderBytes);
        if (peerHealth != null && byronEbHead != null && byronEbHead.getConsensusData() != null) {
            peerHealth.recordHeaderProgress(
                    byronEbHead.getConsensusData().getAbsoluteSlot(),
                    byronEbHead.getConsensusData().getDifficulty().longValue(),
                    System.currentTimeMillis());
        }

        // Resume BodyFetchManager if paused and headers are flowing after intersection
        callbacks.resumeBodyFetchOnHeaderFlow();
    }

    // ================================================================
    // BlockFetch Events - Delegate to BodyFetchManager
    // ================================================================

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        long startedNanos = System.nanoTime();
        Long slot = block != null && block.getHeader() != null && block.getHeader().getHeaderBody() != null
                ? block.getHeader().getHeaderBody().getSlot()
                : null;
        Long blockNumber = block != null && block.getHeader() != null && block.getHeader().getHeaderBody() != null
                ? block.getHeader().getHeaderBody().getBlockNumber()
                : null;
        if (!acceptsCurrentGeneration("Shelley body block")) {
            return;
        }
        try {
            recordShelleyBodyReceived(block);

            Runnable apply = () -> {
                // Delegate block body processing to BodyFetchManager
                bodyFetchManager.onBlock(era, block, transactions);

                // Update sync progress tracking in Yano
                callbacks.updateSyncProgress();

                // Notify server about new block availability (only during STEADY_STATE)
                callbacks.notifyServerNewBlockStored();
            };
            if (hasLedgerApplyProcessor()) {
                enqueueApply("Shelley block " + blockNumber, estimatedCborBytes(block != null ? block.getCbor() : null), apply);
            } else {
                apply.run();
            }
        } finally {
            logSlowBodyCallback("Shelley", slot, blockNumber, startedNanos);
        }
    }

    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        long startedNanos = System.nanoTime();
        Long slot = byronBlock != null && byronBlock.getHeader() != null
                && byronBlock.getHeader().getConsensusData() != null
                ? byronBlock.getHeader().getConsensusData().getAbsoluteSlot()
                : null;
        Long blockNumber = byronBlock != null && byronBlock.getHeader() != null
                && byronBlock.getHeader().getConsensusData() != null
                ? byronBlock.getHeader().getConsensusData().getDifficulty().longValue()
                : null;
        if (!acceptsCurrentGeneration("Byron body block")) {
            return;
        }
        try {
            recordByronBodyReceived(byronBlock);

            Runnable apply = () -> {
                // Delegate Byron block processing to BodyFetchManager
                bodyFetchManager.onByronBlock(byronBlock);

                // Update sync progress tracking in Yano
                callbacks.updateSyncProgress();

                // Notify server about new block availability (only during STEADY_STATE)
                callbacks.notifyServerNewBlockStored();
            };
            if (hasLedgerApplyProcessor()) {
                enqueueApply("Byron block " + blockNumber,
                        estimatedCborBytes(byronBlock != null ? byronBlock.getCbor() : null), apply);
            } else {
                apply.run();
            }
        } finally {
            logSlowBodyCallback("Byron", slot, blockNumber, startedNanos);
        }
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        long startedNanos = System.nanoTime();
        Long slot = byronEbBlock != null && byronEbBlock.getHeader() != null
                && byronEbBlock.getHeader().getConsensusData() != null
                ? byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot()
                : null;
        Long blockNumber = byronEbBlock != null && byronEbBlock.getHeader() != null
                && byronEbBlock.getHeader().getConsensusData() != null
                ? byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue()
                : null;
        if (!acceptsCurrentGeneration("Byron EB body block")) {
            return;
        }
        try {
            recordByronEbBodyReceived(byronEbBlock);

            Runnable apply = () -> {
                // Delegate Byron EB block processing to BodyFetchManager
                bodyFetchManager.onByronEbBlock(byronEbBlock);

                // Update sync progress tracking in Yano
                callbacks.updateSyncProgress();

                // Notify server about new block availability (only during STEADY_STATE)
                callbacks.notifyServerNewBlockStored();
            };
            if (hasLedgerApplyProcessor()) {
                enqueueApply("Byron EB block " + blockNumber,
                        estimatedCborBytes(byronEbBlock != null ? byronEbBlock.getCbor() : null), apply);
            } else {
                apply.run();
            }
        } finally {
            logSlowBodyCallback("ByronEB", slot, blockNumber, startedNanos);
        }
    }

    @Override
    public void batchStarted() {
        Runnable work = () -> {
            if (peerHealth != null) {
                peerHealth.markBodyFetchStarted(System.currentTimeMillis());
            }
            // Delegate batch start to BodyFetchManager
            bodyFetchManager.batchStarted();
        };
        if (hasLedgerApplyProcessor()) {
            observeControlFuture("batchStarted", ledgerApplyProcessor.enqueueBatchStarted(ledgerGeneration, work));
        } else {
            work.run();
        }
    }

    @Override
    public void batchDone() {
        Runnable work = () -> {
            if (peerHealth != null) {
                peerHealth.markBodyFetchCompleted();
            }
            // Delegate batch completion to BodyFetchManager
            bodyFetchManager.batchDone();
        };
        if (hasLedgerApplyProcessor()) {
            observeControlFuture("batchDone", ledgerApplyProcessor.enqueueBatchDone(ledgerGeneration, work));
        } else {
            work.run();
        }
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        Runnable work = () -> {
            if (peerHealth != null) {
                peerHealth.markBodyFetchCompleted();
            }
            // Delegate no block found event to BodyFetchManager
            bodyFetchManager.noBlockFound(from, to);
        };
        if (hasLedgerApplyProcessor()) {
            observeControlFuture("noBlockFound", ledgerApplyProcessor.enqueueNoBlockFound(ledgerGeneration, work));
        } else {
            work.run();
        }
    }

    // ================================================================
    // Control Events - Coordinate Between Components
    // ================================================================

    @Override
    public void intersactFound(Tip tip, Point point) {
        if (!acceptsCurrentGeneration("intersection found")) {
            return;
        }
        // Notify HeaderSyncManager about intersection
        headerSyncManager.intersactFound(tip, point);

        // Update sync phase in Yano for rollback classification
        callbacks.onIntersectionFound();

        // If we're already near the remote tip, transition to STEADY_STATE immediately
        callbacks.maybeFastTransitionToSteadyState(tip);

        log.info("Intersection found at point: {} - notified both header manager and Yano", point);
    }

    @Override
    public void intersactNotFound(Tip tip) {
        if (!acceptsCurrentGeneration("intersection not found")) {
            return;
        }
        // Notify HeaderSyncManager about intersection not found
        headerSyncManager.intersactNotFound(tip);

        log.warn("Intersection not found for tip: {} - notified header manager", tip);
    }

    @Override
    public void onRollback(Point point) {
        enterRollbackCallback();
        try {
            Runnable work = () -> {
                if (peerHealth != null) {
                    peerHealth.markBodyFetchCompleted();
                }
                bodyFetchManager.onRollback(point);
                // Delegate rollback handling to Yano for classification and coordination
                // Yano will pause/resume BodyFetchManager and handle server notifications
                callbacks.handleChainSyncRollback(point);

                log.info("Rollback to point: {} - delegated to Yano for coordination", point);
            };
            if (hasLedgerApplyProcessor()) {
                CompletableFuture<LedgerApplyProcessor.Outcome> rollback =
                        ledgerApplyProcessor.enqueueRollbackAndClose(ledgerGeneration, "rollback " + point, work);
                rollback.whenComplete((outcome, error) -> {
                    if (error != null) {
                        log.warn("Rollback apply control failed: point={}, error={}", point, error.toString(), error);
                    } else if (outcome == LedgerApplyProcessor.Outcome.COMPLETED) {
                        Thread.ofVirtual()
                                .name("YanoRollbackRecovery")
                                .start(() -> callbacks.requestPeerRecovery(PeerRecoveryReason.ROLLBACK));
                    }
                });
            } else {
                work.run();
            }
        } finally {
            leaveRollbackCallback();
        }
    }

    @Override
    public void onDisconnect() {
        boolean currentGeneration = !hasLedgerApplyProcessor()
                || ledgerApplyProcessor.isGenerationOpen(ledgerGeneration);
        // Notify both managers about disconnection
        headerSyncManager.onDisconnect();
        if (currentGeneration) {
            if (peerHealth != null) {
                peerHealth.recordDisconnect(System.currentTimeMillis());
            }
            Runnable work = () -> {
                bodyFetchManager.onDisconnect();
                if (peerHealth != null) {
                    peerHealth.markBodyFetchCompleted();
                }
            };
            if (hasLedgerApplyProcessor()) {
                observeControlFuture("disconnect", ledgerApplyProcessor.enqueueDisconnectAndClose(ledgerGeneration, work));
            } else {
                work.run();
            }
        } else {
            log.debug("Ignoring stale disconnect after ledger generation close: generation={}", ledgerGeneration);
        }
        try {
            if (currentGeneration) {
                callbacks.onPeerDisconnected();
            }
        } catch (Exception e) {
            log.warn("Peer disconnect callback failed", e);
        }

        log.info("Disconnection event - notified both header and body managers");
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        Runnable work = () -> {
            // Delegate parsing errors to BodyFetchManager
            bodyFetchManager.onParsingError(e);
        };
        if (hasLedgerApplyProcessor()) {
            enqueueApply("block parsing error", 0, work);
        } else {
            work.run();
        }
    }

    private void recordShelleyBodyReceived(Block block) {
        if (peerHealth == null || block == null || block.getHeader() == null || block.getHeader().getHeaderBody() == null) {
            return;
        }

        peerHealth.recordBodyReceived(
                block.getHeader().getHeaderBody().getSlot(),
                block.getHeader().getHeaderBody().getBlockNumber(),
                System.currentTimeMillis());
    }

    private boolean hasLedgerApplyProcessor() {
        return ledgerApplyProcessor != null && ledgerGeneration > 0;
    }

    private boolean acceptsCurrentGeneration(String description) {
        if (!hasLedgerApplyProcessor() || ledgerApplyProcessor.isGenerationOpen(ledgerGeneration)) {
            return true;
        }
        log.debug("Ignoring stale callback after ledger generation close: generation={}, description={}",
                ledgerGeneration, description);
        return false;
    }

    private void enqueueApply(String description, long estimatedBytes, Runnable work) {
        CompletableFuture<LedgerApplyProcessor.Outcome> future = ledgerApplyProcessor.enqueueApplyBlock(
                ledgerGeneration,
                description,
                estimatedBytes,
                () -> {
                    work.run();
                    return LedgerApplyProcessor.Outcome.APPLIED;
                });
        observeControlFuture(description, future);
    }

    private void observeControlFuture(String description, CompletableFuture<LedgerApplyProcessor.Outcome> future) {
        future.whenComplete((outcome, error) -> {
            if (error != null) {
                log.warn("Ledger apply item failed before execution: description={}, error={}",
                        description, error.toString(), error);
            }
        });
    }

    private long estimatedCborBytes(String cborHex) {
        if (cborHex == null || cborHex.isEmpty()) {
            return 0;
        }
        return Math.max(1L, cborHex.length() / 2L);
    }

    private void logSlowBodyCallback(String blockType, Long slot, Long blockNumber, long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsedMs >= SLOW_BODY_CALLBACK_WARN_MS) {
            log.warn("Slow Yano body listener callback: type={}, slot={}, block={}, elapsedMs={}, thread={}",
                    blockType, slot, blockNumber, elapsedMs, Thread.currentThread().getName());
        }
    }

    private static long positiveLongProperty(String propertyName, long defaultValue) {
        long value = Long.getLong(propertyName, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    public boolean awaitRollbackCallbackDrain(Duration timeout) throws InterruptedException {
        long timeoutNanos = (timeout != null ? timeout : Duration.ofSeconds(5)).toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (rollbackCallbackDrainMonitor) {
            while (activeRollbackCallbacks.get() > 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(rollbackCallbackDrainMonitor, remainingNanos);
            }
            return true;
        }
    }

    private void enterRollbackCallback() {
        activeRollbackCallbacks.incrementAndGet();
    }

    private void leaveRollbackCallback() {
        if (activeRollbackCallbacks.decrementAndGet() == 0) {
            synchronized (rollbackCallbackDrainMonitor) {
                rollbackCallbackDrainMonitor.notifyAll();
            }
        }
    }

    private void recordByronBodyReceived(ByronMainBlock byronBlock) {
        if (peerHealth == null || byronBlock == null || byronBlock.getHeader() == null
                || byronBlock.getHeader().getConsensusData() == null) {
            return;
        }

        peerHealth.recordBodyReceived(
                byronBlock.getHeader().getConsensusData().getAbsoluteSlot(),
                byronBlock.getHeader().getConsensusData().getDifficulty().longValue(),
                System.currentTimeMillis());
    }

    private void recordByronEbBodyReceived(ByronEbBlock byronEbBlock) {
        if (peerHealth == null || byronEbBlock == null || byronEbBlock.getHeader() == null
                || byronEbBlock.getHeader().getConsensusData() == null) {
            return;
        }

        peerHealth.recordBodyReceived(
                byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot(),
                byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue(),
                System.currentTimeMillis());
    }

}
