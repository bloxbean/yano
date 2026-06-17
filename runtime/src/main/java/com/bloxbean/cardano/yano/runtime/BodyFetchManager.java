package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yano.api.SyncPhase;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockConsensusEvent;
import com.bloxbean.cardano.yano.api.events.EpochTransitionEvent;
import com.bloxbean.cardano.yano.api.events.GenesisBlockEvent;
import com.bloxbean.cardano.yano.api.events.PostEpochTransitionEvent;
import com.bloxbean.cardano.yano.api.events.PreEpochTransitionEvent;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.BlockReceivedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.events.TipChangedEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.runtime.apply.UnrecoverableApplyException;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateRecovery;
import com.bloxbean.cardano.yano.runtime.chain.EraMetadataStore;
import com.bloxbean.cardano.yano.runtime.chain.OriginRollbackCapable;
import com.bloxbean.cardano.yano.runtime.peer.PeerHealth;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * BodyFetchManager handles gap detection and range-based body fetching to complement HeaderSyncManager.
 *
 * This manager monitors the gap between header_tip (latest header) and tip (latest complete block)
 * and automatically fetches missing block bodies using range requests via PeerClient.fetch().
 *
 * Key Features:
 * - Continuous gap monitoring every 500ms
 * - Range-based fetching (up to 100 blocks per batch)
 * - Automatic pause/resume for rollback scenarios
 * - Integration with existing ChainState storage
 * - Virtual thread-based execution for lightweight concurrency
 *
 * This enables true parallel pipeline architecture where headers sync ahead of bodies.
 */
@Slf4j
public class BodyFetchManager implements BlockChainDataListener, Runnable, HeaderAppliedSignal {
    /**
     * Result of attempting to apply one decoded block body to local ledger state.
     */
    public enum BlockApplyResult {
        /**
         * The block was stored and all block-applied listeners completed.
         */
        APPLIED,
        /**
         * The block was an expected stale body, usually an in-flight BlockFetch
         * response from the pre-rollback chain, and was deliberately ignored.
         */
        SKIPPED_STALE
    }

    private static final long SLOW_EPOCH_TRANSITION_WARN_MS =
            positiveLongProperty(YanoPropertyKeys.BodyFetch.SLOW_EPOCH_TRANSITION_WARN_MS, 1_000L);
    private static final long REALTIME_FALLBACK_POLL_MS =
            positiveLongProperty(YanoPropertyKeys.BodyFetch.REALTIME_FALLBACK_POLL_MS, 5_000L);

    private final PeerClient peerClient;
    private final ChainState chainState;
    private final EventBus eventBus;

    // Configuration
    private final long gapThreshold;
    private final int maxBatchSize;
    private final long monitoringIntervalMs;
    private final long tipProximityThreshold;
    private final SyncTipContext syncTipContext;

    // State management
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Thread monitoringThread;
    private volatile SyncPhase syncPhase = SyncPhase.INITIAL_SYNC;
    private final AtomicLong lastAppliedBodySlot = new AtomicLong(-1L);

    // Metrics
    private final AtomicInteger bodiesReceived = new AtomicInteger(0);
    private final AtomicInteger batchesCompleted = new AtomicInteger(0);
    private final AtomicLong lastGapSize = new AtomicLong(0);
    private final AtomicLong totalBlocksFetched = new AtomicLong(0);
    private volatile long startTime;

    // Current batch tracking
    private volatile boolean batchInProgress = false;
    private volatile Point currentBatchFrom;
    private volatile Point currentBatchTo;
    private volatile int currentBatchSize;
    private volatile PeerHealth peerHealth;

    // Epoch transition detection
    private volatile EpochParamProvider epochParamProvider;
    private volatile int previousEpoch = -1;
    private volatile Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier = GenesisBootstrapData::empty;

    // Rollback tracking to prevent storing stale blocks
    private volatile Point lastRollbackPoint = null;

    // Runtime recovery guardrails
    private final AtomicInteger consecutiveStaleBlocks = new AtomicInteger(0);
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
    private static final int STALE_RECOVERY_THRESHOLD = 20; // consecutive stale drops before probing for corruption

    /**
     * Create BodyFetchManager with default configuration.
     */
    public BodyFetchManager(PeerClient peerClient, ChainState chainState, EventBus eventBus) {
        this(peerClient, chainState, eventBus, 10, 100, 500, 10, null);
    }

    /**
     * Create BodyFetchManager with custom configuration.
     *
     * @param peerClient The PeerClient for fetching block ranges
     * @param chainState The ChainState for storage operations
     * @param gapThreshold Minimum gap size to trigger fetching (default: 10 blocks)
     * @param maxBatchSize Maximum blocks per range request (default: 100)
     * @param monitoringIntervalMs Gap monitoring frequency (default: 500ms)
     * @param tipProximityThreshold Maximum gap to consider "at tip" for immediate resume (default: 10 slots)
     */
    public BodyFetchManager(PeerClient peerClient, ChainState chainState, EventBus eventBus,
                           long gapThreshold, int maxBatchSize, long monitoringIntervalMs, long tipProximityThreshold) {
        this(peerClient, chainState, eventBus, gapThreshold, maxBatchSize, monitoringIntervalMs, tipProximityThreshold, null);
    }

    public BodyFetchManager(PeerClient peerClient, ChainState chainState, EventBus eventBus,
                           long gapThreshold, int maxBatchSize, long monitoringIntervalMs, long tipProximityThreshold,
                           SyncTipContext syncTipContext) {
        if (peerClient == null) {
            throw new IllegalArgumentException("PeerClient cannot be null");
        }
        if (chainState == null) {
            throw new IllegalArgumentException("ChainState cannot be null");
        }
        if (eventBus == null) {
            throw new IllegalArgumentException("EventBus cannot be null");
        }
        if (gapThreshold < 1) {
            throw new IllegalArgumentException("Gap threshold must be positive: " + gapThreshold);
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("Max batch size must be positive: " + maxBatchSize);
        }
        if (monitoringIntervalMs < 1) {
            throw new IllegalArgumentException("Monitoring interval must be positive: " + monitoringIntervalMs);
        }

        this.peerClient = peerClient;
        this.chainState = chainState;
        this.eventBus = eventBus;
        this.gapThreshold = gapThreshold;
        this.maxBatchSize = maxBatchSize;
        this.monitoringIntervalMs = monitoringIntervalMs;
        this.tipProximityThreshold = tipProximityThreshold;
        this.syncTipContext = syncTipContext;

        if (log.isInfoEnabled()) {
            log.info("🏗️ BodyFetchManager created with config: gapThreshold={}, maxBatchSize={}, monitoringInterval={}ms, tipProximityThreshold={}",
                    gapThreshold, maxBatchSize, monitoringIntervalMs, tipProximityThreshold);
        }
    }

    /**
     * Start the body fetch manager in a virtual thread.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("BodyFetchManager is already running");
            return;
        }

        startTime = System.currentTimeMillis();
        resetMetrics();
        initializeAppliedBodySlotFromTip();

        // Check if we should immediately resume (when already near tip)
        checkForImmediateResume();

        // Use virtual thread for lightweight concurrency
        monitoringThread = Thread.ofVirtual()
            .name("BodyFetchManager-Monitor")
            .start(this);

        if (log.isInfoEnabled()) {
            log.info("🚀 BodyFetchManager started with monitoring thread: {}", monitoringThread.getName());
        }
    }

    /**
     * Set the epoch param provider for epoch transition detection.
     * Must be called before start() if epoch transition events are needed.
     */
    public void setEpochParamProvider(EpochParamProvider provider) {
        this.epochParamProvider = provider;
    }

    public void setGenesisBootstrapDataSupplier(Supplier<GenesisBootstrapData> supplier) {
        this.genesisBootstrapDataSupplier = supplier != null ? supplier : GenesisBootstrapData::empty;
    }

    public void setPeerHealth(PeerHealth peerHealth) {
        this.peerHealth = peerHealth;
    }

    /**
     * Initialize previousEpoch from the current chain tip so the first epoch
     * transition after startup is correctly detected. Without this, the first
     * epoch boundary after restart (or adhoc rollback) is silently skipped
     * because previousEpoch defaults to -1.
     *
     * <p>Must be called before block processing begins.</p>
     */
    public void initializePreviousEpoch(int epoch) {
        this.previousEpoch = epoch;
        log.info("BodyFetchManager: previousEpoch initialized to {}", epoch);
    }

    /**
     * Stop the body fetch manager.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.warn("BodyFetchManager is not running");
            return;
        }

        Thread thread = monitoringThread;
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(TimeUnit.SECONDS.toMillis(5));
                    if (thread.isAlive()) {
                        log.warn("BodyFetchManager monitoring thread did not stop within timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for BodyFetchManager monitoring thread to stop");
                }
            }
        }

        if (log.isInfoEnabled()) {
            log.info("🛑 BodyFetchManager stopped after running for {}ms",
                    System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Pause body fetching (used during rollback scenarios).
     */
    public void pause() {
        paused.set(true);
        if (log.isDebugEnabled()) {
            log.debug("⏸️ BodyFetchManager paused");
        }
    }

    /**
     * Resume body fetching after pause.
     */
    public void resume() {
        paused.set(false);
        if (log.isDebugEnabled()) {
            log.debug("▶️ BodyFetchManager resumed");
        }
        wakeFetchLoop();
    }

    @Override
    public void onHeaderApplied(long slot, long blockNumber, String blockHash) {
        if (shouldWakeFromHeader(slot)) {
            wakeFetchLoop();
        }
    }

    public void wakeFetchLoop() {
        Thread thread = monitoringThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    /**
     * Main monitoring loop - runs continuously checking for* Uses adaptive monitoring interval based on sync phase:
     * - STEADY_STATE (tip): 100ms for immediate response
     * - INITIAL_SYNC (bulk): 500ms for efficiency
     */
    @Override
    public void run() {
        log.info("📊 BodyFetchManager monitoring thread started");

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!paused.get()) {
                    checkAndFetchBodies();
                }

                // Push-driven wakeups with phase-aware fallback polling.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(getAdaptiveMonitoringInterval()));
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    break;
                }

            } catch (Exception e) {
                log.error("Error in BodyFetchManager monitoring loop", e);
                // Continue running despite errors
            }
        }

        log.info("📊 BodyFetchManager monitoring thread stopped");
    }

    /**
     * Get adaptive monitoring interval based on sync phase.
     * At tip: faster monitoring for immediate response
     * During bulk: slower monitoring for efficiency
     */
    private long getAdaptiveMonitoringInterval() {
        if (isRealtimeFetchMode()) {
            return REALTIME_FALLBACK_POLL_MS;
        }
        return monitoringIntervalMs; // Use configured interval for bulk sync
    }

    /**
     * Check gap and fetch bodies if needed.
     */
    private void checkAndFetchBodies() {
        if (!peerClient.isRunning()) {
            if (log.isTraceEnabled()) {
                log.trace("PeerClient not running, skipping gap check");
            }
            return;
        }

        if (batchInProgress) {
            if (log.isTraceEnabled()) {
                log.trace("Batch in progress, skipping new fetch");
            }
            return;
        }

        long gapSize = calculateGapSize();
        lastGapSize.set(gapSize);

        // Debug logging to understand gap detection
        ChainTip headerTip = chainState.getHeaderTip();
        ChainTip tip = chainState.getTip();

        if (log.isDebugEnabled()) {
            log.debug("🔍 Gap check: headerTip={}, tip={}, gapSize={}, threshold={}",
                    headerTip != null ? "slot=" + headerTip.getSlot() + " block#" + headerTip.getBlockNumber() : "null",
                    tip != null ? "slot=" + tip.getSlot() + " block#" + tip.getBlockNumber() : "null",
                    gapSize, gapThreshold);
        }

        if (shouldFetchBodies(gapSize)) {
            if (log.isDebugEnabled())
                log.debug("📈 Gap detected: {} slots >= threshold {}, triggering body fetch", gapSize, gapThreshold);

            BlockRange range = calculateNextRange();
            if (range != null) {
                fetchBlockRange(range);
            } else {
                log.warn("🚫 No valid range calculated despite gap of {} slots", gapSize);
            }
        }
    }

    /**
     * Calculate gap size between header_tip and tip.
     */
    private long calculateGapSize() {
        ChainTip headerTip = chainState.getHeaderTip();
        ChainTip tip = chainState.getTip();

        if (headerTip == null) {
            return 0; // No headers yet
        }

        if (tip == null) {
            return headerTip.getSlot(); // All headers are ahead
        }

        return headerTip.getSlot() - tip.getSlot();
    }

    /**
     * Determine if bodies should be fetched based on gap size and sync phase.
     *
     * STEADY_STATE (tip sync): Immediate body fetching (gap >= 1 slot)
     * INITIAL_SYNC (bulk): Efficient batching (gap >= configured threshold)
     */
    private boolean shouldFetchBodies(long gapSize) {
        // At tip: fetch immediately when any header is ahead
        if (isRealtimeFetchMode()) {
            return gapSize >= 1; // Immediate body fetching at tip
        }

        // During bulk sync: use configured threshold for efficient batching
        return gapSize >= gapThreshold;
    }

    private boolean isRealtimeFetchMode() {
        return syncPhase == SyncPhase.STEADY_STATE || isNearObservedNetworkTip();
    }

    private boolean shouldWakeFromHeader(long headerSlot) {
        if (syncPhase == SyncPhase.STEADY_STATE) {
            return true;
        }

        long bodySlot = lastAppliedBodySlot.get();
        long networkTipSlot = syncTipContext != null ? syncTipContext.getNetworkTipSlot() : -1L;
        if (networkTipSlot > 0 && bodySlot >= 0
                && Math.max(0L, networkTipSlot - bodySlot) <= tipProximityThreshold) {
            return true;
        }

        if (bodySlot < 0) {
            return headerSlot >= gapThreshold;
        }

        return headerSlot - bodySlot >= gapThreshold;
    }

    private boolean isNearObservedNetworkTip() {
        if (syncTipContext == null) {
            return false;
        }
        long networkTipSlot = syncTipContext.getNetworkTipSlot();
        if (networkTipSlot <= 0) {
            return false;
        }

        ChainTip bodyTip = chainState.getTip();
        ChainTip headerTip = chainState.getHeaderTip();
        long localSlot = bodyTip != null
                ? bodyTip.getSlot()
                : headerTip != null ? headerTip.getSlot() : -1L;
        if (localSlot < 0) {
            return false;
        }

        long distance = Math.max(0L, networkTipSlot - localSlot);
        return distance <= tipProximityThreshold;
    }

    /**
     * Calculate the next range to fetch.
     */
    private BlockRange calculateNextRange() {
        ChainTip tip = chainState.getTip();
        ChainTip headerTip = chainState.getHeaderTip();

        if (headerTip == null) {
            log.warn("No header tip available for range calculation");
            return null;
        }

        // Start from tip + 1 if tip exists, otherwise from first available header
        Point fromPoint;
        if (tip == null) {
            // When starting fresh, find the first available header to start body fetching
            // This handles genesis sync where headers are available but no bodies yet
            log.debug("No body tip yet - looking for first available header to start body fetch");

            // Get the first block/header from chainstate
            // This now works for all networks including mainnet (Byron block 1 at slot 0)
            Point firstHeader = chainState.getFirstBlock();
            if (firstHeader == null) {
                log.debug("No headers available yet - waiting for headers before starting body fetch");
                return null;
            }

            log.debug("Found first header at slot={}, hash={} - starting body fetch from there",
                     firstHeader.getSlot(), firstHeader.getHash());
            fromPoint = firstHeader;
        } else {
            // Find next header after current body tip
            Point currentTipPoint = new Point(tip.getSlot(), HexUtil.encodeHexString(tip.getBlockHash()));
            log.debug("Looking for next header after body tip: slot={}, hash={}",
                     currentTipPoint.getSlot(), currentTipPoint.getHash());

            // Use the new findNextBlockHeader method that looks for headers beyond the body tip
            Point nextPoint = chainState.findNextBlockHeader(currentTipPoint);

            if (nextPoint == null) {
                log.warn("❌ No next header found after body tip: slot={}, hash={}. Headers may not be available yet.",
                        currentTipPoint.getSlot(), currentTipPoint.getHash());
                return null;
            }
            log.debug("Found next header: slot={}, hash={}", nextPoint.getSlot(), nextPoint.getHash());
            fromPoint = nextPoint;
        }

        // Find the end point by getting the last point after maxBatchSize blocks
        // This automatically handles the fact that not every slot has a block in Cardano
        Point toPoint = chainState.findLastPointAfterNBlocks(fromPoint, maxBatchSize);
        int rangeSize;
        if (toPoint == null) {
            // Fallback: if we couldn't find an end point beyond fromPoint,
            // request a single block at fromPoint. This happens near tip
            // or during sparse-slot periods when only one header is available.
            if (log.isDebugEnabled()) {
                log.debug("No end point beyond slot {}. Falling back to single-block fetch.", fromPoint.getSlot());
            }
            toPoint = fromPoint;
            rangeSize = 1;
        } else {
            // The range size is based on the actual number of blocks found, not slot difference
            // Since findLastPointAfterNBlocks returns after maxBatchSize blocks, the size is at most maxBatchSize
            // The server will return what's available within this range
            rangeSize = maxBatchSize;
        }

        if (log.isDebugEnabled()) {
            log.debug("📦 Calculated range: from={}, to={}, size={}",
                     fromPoint.getSlot(), toPoint.getSlot(), rangeSize);
        }

        return new BlockRange(fromPoint, toPoint, rangeSize);
    }

    /**
     * Fetch a block range using PeerClient.
     */
    private void fetchBlockRange(BlockRange range) {
        if (batchInProgress) {
            log.warn("Batch already in progress, skipping new fetch");
            return;
        }

        batchInProgress = true;
        currentBatchFrom = range.from;
        currentBatchTo = range.to;
        currentBatchSize = range.size;

        if (log.isDebugEnabled()) {
            log.debug("🔄 Fetching block range: from slot {} to slot {} ({} blocks)",
                    range.from.getSlot(), range.to.getSlot(), range.size);
        }

        try {
            peerClient.fetch(range.from, range.to);
        } catch (Exception e) {
            log.error("Failed to fetch block range: from={}, to={}", range.from, range.to, e);
            batchInProgress = false; // Reset on error
        }
    }

    // ================================================================
    // BlockChainDataListener Implementation
    // ================================================================

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        applyBlock(era, block, transactions);
    }

    public BlockApplyResult applyBlock(Era era, Block block, List<Transaction> transactions) {
        // Store complete block and update tip
        if (block == null || block.getHeader() == null || block.getHeader().getHeaderBody() == null) {
            throw new RuntimeException("Received null or incomplete Shelley block");
        }

        long slot = -1;
        long blockNumber = -1;
        String hash = null;
        ChainTip tipBeforeStore = null;
        boolean blockStored = false;

        try {
            slot = block.getHeader().getHeaderBody().getSlot();
            blockNumber = block.getHeader().getHeaderBody().getBlockNumber();
            hash = block.getHeader().getHeaderBody().getBlockHash();

            // Check for stale blocks that arrived after rollback
            if (isStaleBlock(blockNumber, slot, hash, false)) {
                log.warn("🗑️ DISCARDED STALE BLOCK: Block #{} at slot {} arrived after rollback - skipping storage",
                        blockNumber, slot);
                onStaleBlockObserved();
                return BlockApplyResult.SKIPPED_STALE;
            }

            // Publish BlockReceived before storage
            EventMetadata recvMeta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(slot)
                    .blockNo(blockNumber)
                    .blockHash(hash)
                    .build();
            eventBus.publish(new BlockReceivedEvent(era, slot, blockNumber, hash, block), recvMeta, PublishOptions.builder().build());

            // Store the complete block (header + body)
            // Require CBOR bytes for proper storage
            if (block.getCbor() == null || block.getCbor().isEmpty()) {
                throw new RuntimeException("Block CBOR is required but was null/empty for block: " + hash);
            }

            byte[] blockBytes;
            try {
                blockBytes = HexUtil.decodeHexString(block.getCbor());
            } catch (Exception e) {
                throw new RuntimeException("Invalid block CBOR hex format for block: " + hash + ", CBOR: " + block.getCbor(), e);
            }

            byte[] hashBytes;
            try {
                hashBytes = HexUtil.decodeHexString(hash);
            } catch (Exception e) {
                throw new RuntimeException("Invalid block hash hex format: " + hash, e);
            }

            // Consensus check — let plugins approve/reject this block
            var consensusEvent = new BlockConsensusEvent(slot, blockNumber, hash, blockBytes);
            eventBus.publish(consensusEvent, recvMeta, PublishOptions.builder().build());
            if (consensusEvent.isRejected()) {
                String rejectionReason = consensusEvent.rejections().stream()
                        .map(r -> r.source() + ": " + r.reason())
                        .collect(Collectors.joining("; "));
                log.warn("Block #{} at slot {} rejected by consensus: {}", blockNumber, slot, rejectionReason);
                throw new RuntimeException("Block rejected by consensus: block=" + blockNumber
                        + ", slot=" + slot + ", reason=" + rejectionReason);
            }

            tipBeforeStore = chainState.getTip();
            boolean freshChain = tipBeforeStore == null;
            chainState.storeBlock(
                hashBytes,
                blockNumber,
                slot,
                blockBytes
            );
            blockStored = true;

            persistEraStartSlotIfNeeded(era, slot);

            // successful store resets stale counter
            consecutiveStaleBlocks.set(0);

            EventMetadata appMeta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(slot)
                    .blockNo(blockNumber)
                    .blockHash(hash)
                    .build();
            PublishOptions appOptions = PublishOptions.builder().build();

            publishGenesisBlockEventIfNeeded(freshChain, era, slot, blockNumber, hash, appMeta, appOptions);

            // Detect epoch transition and publish PreEpochTransitionEvent BEFORE BlockAppliedEvent
            publishEpochTransitionEventsIfNeeded(slot, blockNumber);

            // Publish BlockApplied after storage
            eventBus.publish(new BlockAppliedEvent(era, slot, blockNumber, hash, block), appMeta, appOptions);
            recordBodyApplied(slot, blockNumber);

            // Publish TipChanged if tip advanced
            var _newTip = chainState.getTip();
            if (_newTip != null) {
                EventMetadata tipMeta = EventMetadata.builder()
                        .origin("runtime")
                        .slot(_newTip.getSlot())
                        .blockNo(_newTip.getBlockNumber())
                        .blockHash(HexUtil.encodeHexString(_newTip.getBlockHash()))
                        .build();
                eventBus.publish(new TipChangedEvent(
                        null, null, null,
                        _newTip.getSlot(), _newTip.getBlockNumber(), HexUtil.encodeHexString(_newTip.getBlockHash())
                ), tipMeta, PublishOptions.builder().build());
            }

            bodiesReceived.incrementAndGet();
            totalBlocksFetched.incrementAndGet();

            // Distance-aware logging using cached network tip from HeaderSyncManager
            if (shouldLogEveryBlock(slot)) {
                log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, block.getEra());
            } else if (totalBlocksFetched.get() % 100 == 0) {
                log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, block.getEra());
            }

            if (log.isDebugEnabled() && bodiesReceived.get() % 10 == 0) {
                log.debug("📦 Received {} complete blocks, latest: slot={}, block={}",
                         bodiesReceived.get(), slot, blockNumber);
            }

            return BlockApplyResult.APPLIED;

        } catch (Exception e) {
            if (blockStored) {
                compensateFailedPostStoreApply(tipBeforeStore, slot, blockNumber, hash, e);
            }

            // Check for continuity violation — indicates fork-induced index/body mismatch.
            // Instead of cascading failure, treat as stale and let recovery handle it.
            boolean isContinuityViolation = false;
            if (e.getMessage() != null && e.getMessage().contains("CONTINUITY VIOLATION")) {
                isContinuityViolation = true;
            } else if (e.getCause() != null && e.getCause().getMessage() != null
                    && e.getCause().getMessage().contains("CONTINUITY VIOLATION")) {
                isContinuityViolation = true;
            }
            if (isContinuityViolation) {
                log.warn("Continuity violation — treating as stale for recovery: {}",
                        e.getMessage() != null ? e.getMessage().substring(0, Math.min(120, e.getMessage().length())) : "unknown");
                if (consecutiveStaleBlocks.incrementAndGet() >= STALE_RECOVERY_THRESHOLD) {
                    onStaleBlockObserved();
                }
                throw e;
            }

            log.error("Failed to store complete block: {}",
                     block != null && block.getHeader() != null && block.getHeader().getHeaderBody() != null ?
                     block.getHeader().getHeaderBody().getBlockHash() : "unknown", e);
            throw e; // Re-throw non-continuity errors
        }
    }

    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        applyByronBlock(byronBlock);
    }

    public BlockApplyResult applyByronBlock(ByronMainBlock byronBlock) {
        if (byronBlock == null || byronBlock.getHeader() == null) {
            throw new RuntimeException("Received null or incomplete Byron block");
        }

        long slot = -1;
        long blockNumber = -1;
        String hash = null;
        ChainTip tipBeforeStore = null;
        boolean blockStored = false;

        try {
            // Handle Byron main block storage
            slot = byronBlock.getHeader().getConsensusData().getAbsoluteSlot();
            blockNumber = byronBlock.getHeader().getConsensusData().getDifficulty().longValue();
            hash = byronBlock.getHeader().getBlockHash();

            // Check for stale blocks that arrived after rollback
            if (isStaleBlock(blockNumber, slot, hash, false)) {
                log.warn("🗑️ DISCARDED STALE BLOCK: Block #{} at slot {} arrived after rollback - skipping storage",
                        blockNumber, slot);
                onStaleBlockObserved();
                return BlockApplyResult.SKIPPED_STALE;
            }

            // Publish BlockReceived before storage
            EventMetadata recvMeta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(slot)
                    .blockNo(blockNumber)
                    .blockHash(hash)
                    .build();
            // Byron blocks are not Block type; publish with null Block reference
            eventBus.publish(new BlockReceivedEvent(Era.Byron, slot, blockNumber, hash, null), recvMeta, PublishOptions.builder().build());

            // Require CBOR bytes for proper storage
            if (byronBlock.getCbor() == null || byronBlock.getCbor().isEmpty()) {
                throw new RuntimeException("Byron block CBOR is required but was null/empty for block: " + hash);
            }

            byte[] blockBytes;
            try {
                blockBytes = HexUtil.decodeHexString(byronBlock.getCbor());
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron block CBOR hex format for block: " + hash + ", CBOR: " + byronBlock.getCbor(), e);
            }

            byte[] hashBytes;
            try {
                hashBytes = HexUtil.decodeHexString(hash);
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron block hash hex format: " + hash, e);
            }

            // Consensus check — let plugins approve/reject this block
            var consensusEvent = new BlockConsensusEvent(slot, blockNumber, hash, blockBytes);
            eventBus.publish(consensusEvent, recvMeta, PublishOptions.builder().build());
            if (consensusEvent.isRejected()) {
                String rejectionReason = consensusEvent.rejections().stream()
                        .map(r -> r.source() + ": " + r.reason())
                        .collect(Collectors.joining("; "));
                log.warn("Byron block #{} at slot {} rejected by consensus: {}", blockNumber, slot, rejectionReason);
                throw new RuntimeException("Byron block rejected by consensus: block=" + blockNumber
                        + ", slot=" + slot + ", reason=" + rejectionReason);
            }

            tipBeforeStore = chainState.getTip();
            boolean freshChain = tipBeforeStore == null;
            chainState.storeBlock(
                hashBytes,
                blockNumber,
                slot,
                blockBytes
            );
            blockStored = true;

            persistEraStartSlotIfNeeded(Era.Byron, slot);

            // successful store resets stale counter
            consecutiveStaleBlocks.set(0);

            EventMetadata appMeta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(slot)
                    .blockNo(blockNumber)
                    .blockHash(hash)
                    .build();
            PublishOptions appOptions = PublishOptions.builder().build();

            publishGenesisBlockEventIfNeeded(freshChain, Era.Byron, slot, blockNumber, hash, appMeta, appOptions);

            // Detect epoch transition and publish PreEpochTransitionEvent BEFORE BlockAppliedEvent
            publishEpochTransitionEventsIfNeeded(slot, blockNumber);

            // Publish BlockApplied after storage
            eventBus.publish(new BlockAppliedEvent(Era.Byron, slot, blockNumber, hash, null), appMeta, appOptions);
            recordBodyApplied(slot, blockNumber);

            // Publish TipChanged if tip advanced
            var _newTipByron = chainState.getTip();
            if (_newTipByron != null) {
                EventMetadata tipMeta = EventMetadata.builder()
                        .origin("runtime")
                        .slot(_newTipByron.getSlot())
                        .blockNo(_newTipByron.getBlockNumber())
                        .blockHash(HexUtil.encodeHexString(_newTipByron.getBlockHash()))
                        .build();
                eventBus.publish(new TipChangedEvent(
                        null, null, null,
                        _newTipByron.getSlot(), _newTipByron.getBlockNumber(), HexUtil.encodeHexString(_newTipByron.getBlockHash())
                ), tipMeta, PublishOptions.builder().build());
            }

            bodiesReceived.incrementAndGet();
            totalBlocksFetched.incrementAndGet();

            if (shouldLogEveryBlock(slot)) {
                log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, "Byron");
            } else if (totalBlocksFetched.get() % 100 == 0) {
                log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, "Byron");
            }

            if (log.isDebugEnabled()) {
                log.debug("📦 Byron block received: slot={}, hash={}", slot, hash);
            }

            return BlockApplyResult.APPLIED;

        } catch (Exception e) {
            if (blockStored) {
                compensateFailedPostStoreApply(tipBeforeStore, slot, blockNumber, hash, e);
            }
            log.error("Failed to store Byron block: {}",
                     byronBlock != null && byronBlock.getHeader() != null ?
                     byronBlock.getHeader().getBlockHash() : "unknown", e);
            throw e; // Re-throw exception for proper error handling
        }
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        applyByronEbBlock(byronEbBlock);
    }

    public BlockApplyResult applyByronEbBlock(ByronEbBlock byronEbBlock) {
        if (byronEbBlock == null || byronEbBlock.getHeader() == null) {
            throw new RuntimeException("Received null or incomplete Byron EB block");
        }

        long slot = -1;
        long blockNumber = -1;
        String hash = null;
        ChainTip tipBeforeStore = null;
        boolean blockStored = false;

        try {
            // Handle Byron epoch boundary block storage
            slot = byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot();
            blockNumber = byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue();
            hash = byronEbBlock.getHeader().getBlockHash();

            // Check for stale blocks that arrived after rollback
            if (isStaleBlock(blockNumber, slot, hash, true)) {
                log.warn("🗑️ DISCARDED STALE BLOCK: Byron EB Block #{} at slot {} arrived after rollback - skipping storage",
                        blockNumber, slot);
                onStaleBlockObserved();
                return BlockApplyResult.SKIPPED_STALE;
            }

            // Publish BlockReceived before storage
            EventMetadata recvMeta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(slot)
                    .blockNo(blockNumber)
                    .blockHash(hash)
                    .build();
            eventBus.publish(new BlockReceivedEvent(Era.Byron, slot, blockNumber, hash, null), recvMeta, PublishOptions.builder().build());

            // Require CBOR bytes for proper storage
            if (byronEbBlock.getCbor() == null || byronEbBlock.getCbor().isEmpty()) {
                throw new RuntimeException("Byron EB block CBOR is required but was null/empty for block: " + hash);
            }

            byte[] blockBytes;
            try {
                blockBytes = HexUtil.decodeHexString(byronEbBlock.getCbor());
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron EB block CBOR hex format for block: " + hash + ", CBOR: " + byronEbBlock.getCbor(), e);
            }

            byte[] hashBytes;
            try {
                hashBytes = HexUtil.decodeHexString(hash);
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron EB block hash hex format: " + hash, e);
            }

            // Consensus check — let plugins approve/reject this block
            var consensusEvent = new BlockConsensusEvent(slot, blockNumber, hash, blockBytes);
            eventBus.publish(consensusEvent, recvMeta, PublishOptions.builder().build());
            if (consensusEvent.isRejected()) {
                String rejectionReason = consensusEvent.rejections().stream()
                        .map(r -> r.source() + ": " + r.reason())
                        .collect(Collectors.joining("; "));
                log.warn("Byron EB block #{} at slot {} rejected by consensus: {}", blockNumber, slot, rejectionReason);
                throw new RuntimeException("Byron EB block rejected by consensus: block=" + blockNumber
                        + ", slot=" + slot + ", reason=" + rejectionReason);
            }

            tipBeforeStore = chainState.getTip();
            boolean freshChain = tipBeforeStore == null;
            chainState.storeBlock(
                hashBytes,
                blockNumber,
                slot,
                blockBytes
            );
            blockStored = true;

            persistEraStartSlotIfNeeded(Era.Byron, slot);

            // successful store resets stale counter
            consecutiveStaleBlocks.set(0);

            EventMetadata appMeta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(slot)
                    .blockNo(blockNumber)
                    .blockHash(hash)
                    .build();
            PublishOptions appOptions = PublishOptions.builder().build();

            publishGenesisBlockEventIfNeeded(freshChain, Era.Byron, slot, blockNumber, hash, appMeta, appOptions);

            // Detect epoch transition and publish PreEpochTransitionEvent BEFORE BlockAppliedEvent
            publishEpochTransitionEventsIfNeeded(slot, blockNumber);

            // Publish BlockApplied after storage
            eventBus.publish(new BlockAppliedEvent(Era.Byron, slot, blockNumber, hash, null), appMeta, appOptions);
            recordBodyApplied(slot, blockNumber);

            // Publish TipChanged if tip advanced
            var _newTipEb = chainState.getTip();
            if (_newTipEb != null) {
                EventMetadata tipMeta = EventMetadata.builder()
                        .origin("runtime")
                        .slot(_newTipEb.getSlot())
                        .blockNo(_newTipEb.getBlockNumber())
                        .blockHash(HexUtil.encodeHexString(_newTipEb.getBlockHash()))
                        .build();
                eventBus.publish(new TipChangedEvent(
                        null, null, null,
                        _newTipEb.getSlot(), _newTipEb.getBlockNumber(), HexUtil.encodeHexString(_newTipEb.getBlockHash())
                ), tipMeta, PublishOptions.builder().build());
            }

            bodiesReceived.incrementAndGet();
            totalBlocksFetched.incrementAndGet();

            if (log.isDebugEnabled()) {
                log.debug("📦 Byron EB block received: slot={}, hash={}", slot, hash);
            }

            return BlockApplyResult.APPLIED;

        } catch (Exception e) {
            if (blockStored) {
                compensateFailedPostStoreApply(tipBeforeStore, slot, blockNumber, hash, e);
            }
            log.error("Failed to store Byron EB block: {}",
                     byronEbBlock != null && byronEbBlock.getHeader() != null ?
                     byronEbBlock.getHeader().getBlockHash() : "unknown", e);
            throw e; // Re-throw exception for proper error handling
        }
    }

    @Override
    public void batchStarted() {
        if (log.isDebugEnabled()) {
            log.debug("📥 Batch fetch started: from={}, to={}, expected size={}",
                     currentBatchFrom != null ? currentBatchFrom.getSlot() : "unknown",
                     currentBatchTo != null ? currentBatchTo.getSlot() : "unknown",
                     currentBatchSize);
        }
    }

    @Override
    public void batchDone() {
        batchInProgress = false;
        batchesCompleted.incrementAndGet();

        if (log.isDebugEnabled()) {
            log.debug("✅ Batch fetch completed: from={}, to={}, received {} blocks",
                     currentBatchFrom != null ? currentBatchFrom.getSlot() : "unknown",
                     currentBatchTo != null ? currentBatchTo.getSlot() : "unknown",
                     currentBatchSize);
        }

        // Reset batch tracking
        currentBatchFrom = null;
        currentBatchTo = null;
        currentBatchSize = 0;
        wakeFetchLoop();
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        log.warn("⚠️ No blocks found in range: from={}, to={}", from, to);
        batchInProgress = false; // Reset state
    }

    @Override
    public void onRollback(Point point) {
        log.info("🔄 Rollback detected to point: {}", point);
        paused.set(true);
        // Store the rollback point to prevent storing stale blocks
        lastRollbackPoint = point;
        // Reset any in-progress batch
        batchInProgress = false;
        currentBatchFrom = null;
        currentBatchTo = null;
        currentBatchSize = 0;
        consecutiveStaleBlocks.set(0);
        resetAppliedBodySlotAfterRollback(point);
    }

    @Override
    public void onDisconnect() {
        log.info("💔 Connection lost - pausing body fetch until reconnection");
        batchInProgress = false; // Reset state on disconnect
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        log.error("🚨 Block parsing error in BodyFetchManager", e);
        throw e;
    }

    private void compensateFailedPostStoreApply(ChainTip tipBeforeStore,
                                                long failedSlot,
                                                long failedBlockNumber,
                                                String failedHash,
                                                Throwable failure) {
        long rollbackSlot = tipBeforeStore != null ? tipBeforeStore.getSlot() : -1L;
        String rollbackHash = tipBeforeStore != null && tipBeforeStore.getBlockHash() != null
                ? HexUtil.encodeHexString(tipBeforeStore.getBlockHash())
                : null;
        log.error("Block apply failed after ChainState store; rolling back local state to slot {} before recovery "
                        + "(failed block={}, slot={}, hash={})",
                rollbackSlot, failedBlockNumber, failedSlot, failedHash, failure);
        try {
            rollbackChainStateAfterPostStoreFailure(tipBeforeStore, rollbackSlot);
        } catch (Throwable rollbackFailure) {
            throw unrecoverableCompensationFailure(
                    "Failed to roll back ChainState after post-store apply failure; automatic recovery cannot safely continue",
                    failure,
                    rollbackFailure);
        }

        try {
            EventMetadata rollbackMeta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(rollbackSlot)
                    .blockHash(rollbackHash)
                    .build();
            eventBus.publish(new RollbackEvent(new Point(rollbackSlot, rollbackHash), true),
                    rollbackMeta, PublishOptions.builder().build());
        } catch (Throwable rollbackEventFailure) {
            throw unrecoverableCompensationFailure(
                    "Failed to publish compensating RollbackEvent after post-store apply failure; automatic recovery cannot safely continue",
                    failure,
                    rollbackEventFailure);
        }
    }

    private void rollbackChainStateAfterPostStoreFailure(ChainTip tipBeforeStore, long rollbackSlot) {
        if (tipBeforeStore == null) {
            rollbackChainStateToOrigin();
            if (chainState.getTip() != null || chainState.getHeaderTip() != null) {
                throw new IllegalStateException("ChainState rollback to origin left a non-empty tip");
            }
            return;
        }

        chainState.rollbackTo(rollbackSlot);
        ChainTip currentTip = chainState.getTip();
        if (!sameTip(currentTip, tipBeforeStore)) {
            throw new IllegalStateException("ChainState rollback ended at "
                    + describeTip(currentTip) + " instead of " + describeTip(tipBeforeStore));
        }
    }

    private void rollbackChainStateToOrigin() {
        if (chainState instanceof OriginRollbackCapable originRollback) {
            originRollback.rollbackToOrigin();
            return;
        }
        chainState.rollbackTo(0L);
    }

    private static boolean sameTip(ChainTip left, ChainTip right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.getSlot() == right.getSlot()
                && left.getBlockNumber() == right.getBlockNumber()
                && java.util.Arrays.equals(left.getBlockHash(), right.getBlockHash());
    }

    private static String describeTip(ChainTip tip) {
        if (tip == null) {
            return "origin";
        }
        return "slot=" + tip.getSlot()
                + ", block=" + tip.getBlockNumber()
                + ", hash=" + HexUtil.encodeHexString(tip.getBlockHash());
    }

    private static UnrecoverableApplyException unrecoverableCompensationFailure(String message,
                                                                               Throwable originalFailure,
                                                                               Throwable compensationFailure) {
        log.error(message, compensationFailure);
        UnrecoverableApplyException unrecoverable =
                new UnrecoverableApplyException(message, originalFailure);
        unrecoverable.addSuppressed(compensationFailure);
        return unrecoverable;
    }

    // ================================================================
    // Corruption Probing on Repeated Stale Blocks
    // ================================================================

    private void onStaleBlockObserved() {
        int count = consecutiveStaleBlocks.incrementAndGet();

        // Early exit if below threshold or already recovering
        if (count < STALE_RECOVERY_THRESHOLD || recoveryInProgress.get()) return;

        // Single-flight guard
        if (!recoveryInProgress.compareAndSet(false, true)) return;

        log.warn("⚠️ Many consecutive stale blocks observed ({}). Probing for corruption...", count);

        Thread.ofVirtual().start(() -> {
            try {
                // Pause fetching during probe to avoid churn
                paused.set(true);

                if (chainState instanceof ChainStateRecovery recovery) {
                    if (recovery.detectCorruption()) {
                        log.warn("🚨 Corruption detected during runtime probe - attempting recovery");
                        recovery.recoverFromCorruption();
                        log.info("✅ Recovery completed after stale-block probe");
                        // After recovery, reset counters and allow fetching to resume
                        consecutiveStaleBlocks.set(0);
                    } else {
                        log.debug("No corruption detected during stale-block probe");
                    }
                } else {
                    log.debug("ChainState is not RocksDB-backed; skipping runtime corruption probe");
                }
            } catch (Exception e) {
                log.warn("Runtime recovery probe failed: {}", e.toString());
            } finally {
                paused.set(false);
                recoveryInProgress.set(false);
            }
        });
    }

    // ================================================================
    // Status and Metrics
    // ================================================================

    /**
     * Get current status of the BodyFetchManager.
     */
    public BodyFetchStatus getStatus() {
        ChainTip tip = chainState.getTip();
        ChainTip headerTip = chainState.getHeaderTip();

        return new BodyFetchStatus(
            running.get(),
            paused.get(),
            batchInProgress,
            bodiesReceived.get(),
            batchesCompleted.get(),
            calculateGapSize(),  // Calculate gap size on demand instead of using cached value
            tip != null ? tip.getSlot() : null,
            tip != null ? tip.getBlockNumber() : null,
            headerTip != null ? headerTip.getSlot() : null,
            headerTip != null ? headerTip.getBlockNumber() : null,
            totalBlocksFetched.get(),
            System.currentTimeMillis() - startTime
        );
    }

    /**
     * Reset metrics (useful for testing).
     */
    public void resetMetrics() {
        bodiesReceived.set(0);
        batchesCompleted.set(0);
        lastGapSize.set(0);
        totalBlocksFetched.set(0);
        startTime = System.currentTimeMillis();
    }

    /**
     * Check if BodyFetchManager is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Check if BodyFetchManager is paused.
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Get current gap size (recalculated on demand).
     */
    public long getCurrentGapSize() {
        return calculateGapSize();
    }

    // ================================================================
    // Helper Classes and Methods
    // ================================================================

    /**
     * Check if an incoming block is stale or would create a gap.
     *
     * A block is considered stale/invalid if:
     * 1. The block would create a gap (missing prerequisite blocks)
     * 2. The block is not the immediate next block after current tip
     * 3. A rollback occurred and the block is beyond the rollback point with gaps
     *
     * @param blockNumber The block number of the incoming block
     * @param slot The slot of the incoming block
     * @param hash The hash of the incoming block
     * @return true if the block should be discarded as stale/invalid
     */
    private boolean isStaleBlock(long blockNumber, long slot, String hash, boolean isEbb) {
        try {
            // If the header for this block already exists in chainstate, the block body
            // is always accepted. This handles body-backfill where HeaderSyncManager has
            // already synced headers (advancing the tip) and BodyFetchManager fetches
            // bodies backwards from genesis. Without this check, blocks behind the tip
            // would be incorrectly rejected as "stale".
            try {
                byte[] headerBytes = chainState.getBlockHeader(HexUtil.decodeHexString(hash));
                if (headerBytes != null) {
                    // Header exists — but also verify prerequisite: block N-1 body must exist.
                    // After a fork/rollback, the header indices may point to a different hash
                    // than the stored body. Without this check, storeBlock would fail with
                    // CONTINUITY VIOLATION because getBlockByNumber(N-1) follows new indices
                    // to a hash whose body doesn't exist.
                    if (blockNumber > 1) {
                        // Verify prerequisite: block N-1 body must exist.
                        // This is the same check that storeBlock performs — doing it here prevents
                        // the CONTINUITY VIOLATION exception and allows stale-block recovery instead.
                        // The body read is cached by RocksDB, so the duplicate read in storeBlock is cheap.
                        byte[] prevBlock = chainState.getBlockByNumber(blockNumber - 1);
                        if (prevBlock == null) {
                            log.warn("Header exists for block #{} but prerequisite block #{} body missing — treating as stale (possible fork mismatch)",
                                    blockNumber, blockNumber - 1);
                            return true;
                        }
                    }
                    if (log.isDebugEnabled())
                        log.debug("Header exists for block #{} at slot {} (hash={}), accepting body for backfill",
                                blockNumber, slot, hash.substring(0, Math.min(16, hash.length())));
                    return false;
                }
            } catch (Exception e) {
                // Header lookup failed — fall through to other checks
                if (log.isDebugEnabled())
                    log.debug("Header lookup failed for block #{}: {}", blockNumber, e.getMessage());
            }

            boolean inRollbackTail = lastRollbackPoint != null && slot > lastRollbackPoint.getSlot();
            if (inRollbackTail) {
                log.warn("🚫 Post-rollback block #{} at slot {} has no matching header after rollback to slot {} - treating as stale",
                        blockNumber, slot, lastRollbackPoint.getSlot());
                return true;
            }

            // Get current tip to understand the current state
            ChainTip currentTip = chainState.getTip();

            if (currentTip == null) {
                // If no tip exists:
                // - Allow Byron EBB at genesis (blockNumber may be 0)
                // - Allow main block #0 or #1 as the first block
                if (isEbb) {
                    if (log.isDebugEnabled())
                        log.debug("No tip exists, allowing Byron EBB at slot {} (blockNo={})", slot, blockNumber);
                    return false;
                }
                if (blockNumber <= 1) {
                    if (log.isDebugEnabled())
                        log.debug("No tip exists, allowing block #{} at slot {} as first block", blockNumber, slot);
                    return false;
                }
                if (log.isDebugEnabled())
                    log.debug("No tip exists but incoming block #{} is not allowed as first block - marking as stale", blockNumber);
                return true;
            }

            // Byron EBB handling: allow same-number block at a strictly greater slot
            // when header for that slot exists, since EBB shares difficulty with the prior main block.
            long expectedNextBlockNumber = currentTip.getBlockNumber() + 1;
            if (blockNumber != expectedNextBlockNumber) {
                // Permit special case: same block number but higher slot with a known header (EBB).
                if (isEbb && blockNumber == currentTip.getBlockNumber() && slot > currentTip.getSlot()) {
                    // For EBBs number_by_slot is intentionally not populated; verify by header existence instead.
                    boolean headerPresent;
                    try {
                        headerPresent = chainState.getBlockHeader(HexUtil.decodeHexString(hash)) != null;
                    } catch (Exception e) {
                        headerPresent = false;
                    }
                    if (headerPresent) {
                        if (log.isDebugEnabled())
                            log.debug("Byron EBB allowance: header present for hash {} at slot {} (same blockNumber {}), accepting",
                                    hash, slot, blockNumber);
                        // fall through to prerequisite check
                    } else {
                        if (lastRollbackPoint != null) {
                            log.warn("🚫 Rollback context: rollback was to slot {}, current tip is block {} at slot {}",
                                    lastRollbackPoint.getSlot(), currentTip.getBlockNumber(), currentTip.getSlot());
                        }
                        return true;
                    }
                } else {
                    if (lastRollbackPoint != null) {
                        log.warn("🚫 Rollback context: rollback was to slot {}, current tip is block {} at slot {}",
                                lastRollbackPoint.getSlot(), currentTip.getBlockNumber(), currentTip.getSlot());
                    }
                    return true;
                }
            }

            // Verify the prerequisite block exists (additional safety check)
            if (blockNumber > 1) {
                byte[] previousBlock = chainState.getBlockByNumber(blockNumber - 1);
                if (previousBlock == null) {
                    log.warn("🚫 PREREQUISITE MISSING: Previous block #{} not found for incoming block #{} - marking as stale",
                            blockNumber - 1, blockNumber);
                    return true;
                }
            }

            // Block is accepted
            return false;

        } catch (Exception e) {
            log.warn("Error checking if block #{} is stale - marking as stale for safety: {}", blockNumber, e.getMessage());
            return true; // If we can't determine safely, discard the block
        }
    }

    /**
     * Represents a block range to fetch.
     */
    private static class BlockRange {
        final Point from;
        final Point to;
        final int size;

        BlockRange(Point from, Point to, int size) {
            this.from = from;
            this.to = to;
            this.size = size;
        }
    }

    /**
     * Status information for BodyFetchManager.
     */
    public static class BodyFetchStatus {
        public final boolean active;
        public final boolean paused;
        public final boolean batchInProgress;
        public final int bodiesReceived;
        public final int batchesCompleted;
        public final long currentGapSize;
        public final Long lastBodySlot;
        public final Long lastBodyBlockNumber;
        public final Long lastHeaderSlot;
        public final Long lastHeaderBlockNumber;
        public final long totalBlocksFetched;
        public final long uptimeMs;

        public BodyFetchStatus(boolean active, boolean paused, boolean batchInProgress,
                              int bodiesReceived, int batchesCompleted, long currentGapSize,
                              Long lastBodySlot, Long lastBodyBlockNumber,
                              Long lastHeaderSlot, Long lastHeaderBlockNumber,
                              long totalBlocksFetched, long uptimeMs) {
            this.active = active;
            this.paused = paused;
            this.batchInProgress = batchInProgress;
            this.bodiesReceived = bodiesReceived;
            this.batchesCompleted = batchesCompleted;
            this.currentGapSize = currentGapSize;
            this.lastBodySlot = lastBodySlot;
            this.lastBodyBlockNumber = lastBodyBlockNumber;
            this.lastHeaderSlot = lastHeaderSlot;
            this.lastHeaderBlockNumber = lastHeaderBlockNumber;
            this.totalBlocksFetched = totalBlocksFetched;
            this.uptimeMs = uptimeMs;
        }
    }

    /**
     * Set the current sync phase. Called by Yano to coordinate logging behavior.
     *
     * @param syncPhase The current sync phase
     */
    public void setSyncPhase(SyncPhase syncPhase) {
        SyncPhase oldPhase = this.syncPhase;
        this.syncPhase = syncPhase;
        if (oldPhase != syncPhase) {
            if (log.isDebugEnabled()) {
                log.debug("🔄 BodyFetchManager sync phase changed: {} -> {}", oldPhase, syncPhase);
            }
            wakeFetchLoop();
        }
    }

    /**
     * Get the current sync phase.
     */
    public SyncPhase getSyncPhase() {
        return syncPhase;
    }

    public long getObservedNetworkTipSlot() {
        return syncTipContext != null ? syncTipContext.getNetworkTipSlot() : -1L;
    }

    /**
     * Check if we're already near tip and should immediately transition to STEADY_STATE.
     * This enables fast resume when restarting a node that's already synced.
     *
     * IMPORTANT: This now compares against network tip, not just header-body gap.
     * For Byron blocks syncing from early epochs, we don't want to incorrectly
     * detect STEADY_STATE just because headers and bodies are synchronized.
     */
    private void checkForImmediateResume() {
        long headerBodyGap = calculateGapSize();

        // Get network tip from peer client
        ChainTip localTip = chainState.getTip();
        Long networkTipSlot = null;

        try {
            if (peerClient != null && peerClient.isRunning()) {
                var networkTipOpt = peerClient.getLatestTip();
                if (networkTipOpt.isPresent()) {
                    networkTipSlot = networkTipOpt.get().getPoint().getSlot();
                    log.debug("📡 Retrieved network tip: slot={}", networkTipSlot);
                } else {
                    log.debug("📡 Network tip not available yet from peer client");
                }
            } else {
                log.debug("📡 PeerClient not running, cannot get network tip");
            }
        } catch (Exception e) {
            log.debug("Could not get network tip for sync phase detection: {}", e.getMessage());
        }

        // Calculate distance from network tip
        long distanceFromNetworkTip = Long.MAX_VALUE;
        if (localTip != null && networkTipSlot != null) {
            distanceFromNetworkTip = networkTipSlot - localTip.getSlot();
        }

        // Only transition to STEADY_STATE if we're actually near the network tip
        // Use a larger threshold (1000 slots) for network tip proximity since Byron blocks
        // are much older than current tip
        long networkTipThreshold = 1000;
        boolean nearNetworkTip = (networkTipSlot != null) && (distanceFromNetworkTip <= networkTipThreshold);

        // IMPORTANT: Default to INITIAL_SYNC if we can't determine network tip
        // This prevents incorrectly detecting STEADY_STATE for Byron blocks
        if (nearNetworkTip && headerBodyGap <= tipProximityThreshold) {
            // Transition immediately to STEADY_STATE for real-time logging
            syncPhase = SyncPhase.STEADY_STATE;

            ChainTip tip = chainState.getTip();
            ChainTip headerTip = chainState.getHeaderTip();

            log.info("⚡ IMMEDIATE RESUME: Already near network tip (distance={} slots <= threshold={})",
                     distanceFromNetworkTip, networkTipThreshold);
            log.info("⚡ Current state: body tip={}, header tip={}, network tip={}",
                     tip != null ? "slot=" + tip.getSlot() : "null",
                     headerTip != null ? "slot=" + headerTip.getSlot() : "null",
                     networkTipSlot != null ? "slot=" + networkTipSlot : "unknown");
            log.info("⚡ Transitioned directly to STEADY_STATE - will log every block");

            // Don't pause since we're already at tip
            paused.set(false);
        } else {
            log.info("📊 Starting INITIAL_SYNC: header-body gap={} slots, network distance={} slots (threshold={})",
                     headerBodyGap,
                     distanceFromNetworkTip != Long.MAX_VALUE ? distanceFromNetworkTip : "unknown",
                     networkTipThreshold);
            log.info("📊 Will log every 100 blocks during initial sync, every block when near tip");
        }
    }

    // Decide if we should log every block by preferring proximity to the network tip from SyncTipContext.
    // If the network tip is not available, fall back to syncPhase.
    private boolean shouldLogEveryBlock(long currentSlot) {
        if (syncTipContext != null) {
            long networkSlot = syncTipContext.getNetworkTipSlot();
            if (networkSlot > 0) {
                long distance = Math.max(0, networkSlot - currentSlot);
                return distance <= tipProximityThreshold;
            }
        }
        return syncPhase == SyncPhase.STEADY_STATE;
    }

    /**
     * Compute epoch number for a given slot using shared EpochSlotCalc.
     */
    private int epochForSlot(long slot) {
        if (epochParamProvider == null) return -1;
        return epochParamProvider.getEpochSlotCalc().slotToEpoch(slot);
    }

    private void publishGenesisBlockEventIfNeeded(boolean freshChain, Era era, long slot, long blockNumber,
                                                  String blockHash, EventMetadata meta, PublishOptions opts) {
        if (!freshChain) return;
        int epoch = epochForSlot(slot);
        if (epoch < 0) return;
        eventBus.publish(new GenesisBlockEvent(era, epoch, slot, blockNumber, blockHash,
                genesisBootstrapDataSupplier.get()), meta, opts);
    }

    private void recordBodyApplied(long slot, long blockNumber) {
        lastAppliedBodySlot.accumulateAndGet(slot, Math::max);
        PeerHealth health = peerHealth;
        if (health != null) {
            health.recordBodyApplied(slot, blockNumber, System.currentTimeMillis());
        }
    }

    private void initializeAppliedBodySlotFromTip() {
        ChainTip tip = chainState.getTip();
        lastAppliedBodySlot.set(tip != null ? tip.getSlot() : -1L);
    }

    private void resetAppliedBodySlotAfterRollback(Point point) {
        if (point == null) {
            lastAppliedBodySlot.set(-1L);
            return;
        }
        lastAppliedBodySlot.updateAndGet(current -> current < 0 ? current : Math.min(current, point.getSlot()));
    }

    /**
     * Persist the current block's era start slot before epoch-boundary processing sees this block.
     * This closes the one-boundary timing hole where the first block of a new era triggers
     * boundary processing before the later BlockAppliedEvent subscriber records the era start.
     */
    private void persistEraStartSlotIfNeeded(Era era, long slot) {
        if (era == null) return;
        if (chainState instanceof EraMetadataStore eraMetadataStore) {
            eraMetadataStore.setEraStartSlot(era.getValue(), slot);
        }
    }

    /**
     * Check if an epoch transition occurred and publish the three-phase epoch boundary events.
     * Must be called BEFORE publishing BlockAppliedEvent.
     *
     * <p>Event order mirrors the Cardano ledger spec's EPOCH rule (shelley-ledger.pdf §17.4):</p>
     * <ol>
     *   <li>{@link PreEpochTransitionEvent}  — Reward calculation, AdaPot update, param finalization</li>
     *   <li>{@link EpochTransitionEvent}     — <b>SNAP</b>: delegation/stake snapshot</li>
     *   <li>{@link PostEpochTransitionEvent} — <b>POOLREAP</b>: pool deposit refunds</li>
     * </ol>
     */
    private void publishEpochTransitionEventsIfNeeded(long slot, long blockNumber) {
        if (epochParamProvider == null) return;
        int currentEpoch = epochForSlot(slot);
        if (currentEpoch < 0) return;

        if (previousEpoch >= 0 && currentEpoch > previousEpoch) {
            long startedNanos = System.nanoTime();
            log.info("Epoch transition detected: {} -> {} at slot {}, block {}",
                    previousEpoch, currentEpoch, slot, blockNumber);
            EventMetadata meta = EventMetadata.builder()
                    .origin("runtime")
                    .slot(slot)
                    .blockNo(blockNumber)
                    .build();
            PublishOptions opts = PublishOptions.builder().build();
            eventBus.publish(new PreEpochTransitionEvent(previousEpoch, currentEpoch, slot, blockNumber),
                    meta, opts);
            eventBus.publish(new EpochTransitionEvent(previousEpoch, currentEpoch, slot, blockNumber),
                    meta, opts);
            eventBus.publish(new PostEpochTransitionEvent(previousEpoch, currentEpoch, slot, blockNumber),
                    meta, opts);
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            if (elapsedMs >= SLOW_EPOCH_TRANSITION_WARN_MS) {
                log.warn("Slow epoch transition processing: {} -> {}, slot={}, block={}, elapsedMs={}, thread={}",
                        previousEpoch, currentEpoch, slot, blockNumber, elapsedMs, Thread.currentThread().getName());
            }
        }
        previousEpoch = currentEpoch;
    }

    private static long positiveLongProperty(String propertyName, long defaultValue) {
        long value = Long.getLong(propertyName, defaultValue);
        return value > 0 ? value : defaultValue;
    }
}
