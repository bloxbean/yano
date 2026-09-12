package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.runtime.chain.MemPool;
import org.yanoproject.runtime.tx.BlockTransactionSelector;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Praos-aware producer for devnet past-time-travel mode.
 * It scans slots sequentially while bootstrapping history, produces only eligible
 * node-1 blocks, and can then switch to wall-clock slot scanning for handover.
 */
@Slf4j
public class SlotLeaderTimeTravelBlockProducer implements BlockProducerService {
    private static final MathContext MC = new MathContext(40);

    private final ChainState chainState;
    private final BlockTransactionSelector transactions;
    private final Supplier<NodeServer> nodeServerSupplier;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final SignedBlockBuilder blockBuilder;
    private final EpochNonceState epochNonceState;
    private final SlotLeaderCheck slotLeaderCheck;
    private final StakeDataProvider stakeDataProvider;
    private final String poolHash;
    private final long genesisTimestamp;
    private final int slotLengthMillis;
    private final int blockTimeMillis;
    private final long sequentialScanLimitSlots;

    private ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private volatile boolean forceSequentialSlots = true;
    private volatile int backfillBlockIntervalSlots = 1;
    private long lastCheckedSlot = -1;
    private int lastStakeEpoch = -1;
    private BigDecimal sigma = BigDecimal.ZERO;

    public SlotLeaderTimeTravelBlockProducer(
            ChainState chainState, MemPool memPool, NodeServer nodeServer,
            EventBus eventBus, ScheduledExecutorService scheduler,
            SignedBlockBuilder blockBuilder, EpochNonceState epochNonceState,
            SlotLeaderCheck slotLeaderCheck, StakeDataProvider stakeDataProvider,
            String poolHash, long genesisTimestamp, int slotLengthMillis,
            int blockTimeMillis, long sequentialScanLimitSlots,
            TransactionValidationService transactionValidatorService, UtxoState utxoState) {
        this(chainState, memPool, () -> nodeServer, eventBus, scheduler, blockBuilder, epochNonceState,
                slotLeaderCheck, stakeDataProvider, poolHash, genesisTimestamp, slotLengthMillis,
                blockTimeMillis, sequentialScanLimitSlots, transactionValidatorService, utxoState);
    }

    public static SlotLeaderTimeTravelBlockProducer withServerSupplier(
            ChainState chainState, MemPool memPool, Supplier<NodeServer> nodeServerSupplier,
            EventBus eventBus, ScheduledExecutorService scheduler,
            SignedBlockBuilder blockBuilder, EpochNonceState epochNonceState,
            SlotLeaderCheck slotLeaderCheck, StakeDataProvider stakeDataProvider,
            String poolHash, long genesisTimestamp, int slotLengthMillis,
            int blockTimeMillis, long sequentialScanLimitSlots,
            TransactionValidationService transactionValidatorService, UtxoState utxoState) {
        return new SlotLeaderTimeTravelBlockProducer(chainState, memPool, nodeServerSupplier, eventBus, scheduler,
                blockBuilder, epochNonceState, slotLeaderCheck, stakeDataProvider, poolHash, genesisTimestamp,
                slotLengthMillis, blockTimeMillis, sequentialScanLimitSlots, transactionValidatorService, utxoState);
    }

    public static SlotLeaderTimeTravelBlockProducer withTransactionSelector(
            ChainState chainState, BlockTransactionSelector transactions, Supplier<NodeServer> nodeServerSupplier,
            EventBus eventBus, ScheduledExecutorService scheduler,
            SignedBlockBuilder blockBuilder, EpochNonceState epochNonceState,
            SlotLeaderCheck slotLeaderCheck, StakeDataProvider stakeDataProvider,
            String poolHash, long genesisTimestamp, int slotLengthMillis,
            int blockTimeMillis, long sequentialScanLimitSlots) {
        return new SlotLeaderTimeTravelBlockProducer(chainState, transactions, nodeServerSupplier, eventBus, scheduler,
                blockBuilder, epochNonceState, slotLeaderCheck, stakeDataProvider, poolHash, genesisTimestamp,
                slotLengthMillis, blockTimeMillis, sequentialScanLimitSlots);
    }

    private SlotLeaderTimeTravelBlockProducer(
            ChainState chainState, MemPool memPool, Supplier<NodeServer> nodeServerSupplier,
            EventBus eventBus, ScheduledExecutorService scheduler,
            SignedBlockBuilder blockBuilder, EpochNonceState epochNonceState,
            SlotLeaderCheck slotLeaderCheck, StakeDataProvider stakeDataProvider,
            String poolHash, long genesisTimestamp, int slotLengthMillis,
            int blockTimeMillis, long sequentialScanLimitSlots,
            TransactionValidationService transactionValidatorService, UtxoState utxoState) {
        this(chainState,
                BlockProducerHelper.transactionSelector(memPool, transactionValidatorService, utxoState),
                nodeServerSupplier,
                eventBus,
                scheduler,
                blockBuilder,
                epochNonceState,
                slotLeaderCheck,
                stakeDataProvider,
                poolHash,
                genesisTimestamp,
                slotLengthMillis,
                blockTimeMillis,
                sequentialScanLimitSlots);
    }

    private SlotLeaderTimeTravelBlockProducer(
            ChainState chainState, BlockTransactionSelector transactions, Supplier<NodeServer> nodeServerSupplier,
            EventBus eventBus, ScheduledExecutorService scheduler,
            SignedBlockBuilder blockBuilder, EpochNonceState epochNonceState,
            SlotLeaderCheck slotLeaderCheck, StakeDataProvider stakeDataProvider,
            String poolHash, long genesisTimestamp, int slotLengthMillis,
            int blockTimeMillis, long sequentialScanLimitSlots) {
        this.chainState = chainState;
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.nodeServerSupplier = nodeServerSupplier != null ? nodeServerSupplier : () -> null;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.blockBuilder = blockBuilder;
        this.epochNonceState = epochNonceState;
        this.slotLeaderCheck = slotLeaderCheck;
        this.stakeDataProvider = stakeDataProvider;
        this.poolHash = poolHash;
        this.genesisTimestamp = genesisTimestamp;
        this.slotLengthMillis = slotLengthMillis;
        this.blockTimeMillis = blockTimeMillis;
        this.sequentialScanLimitSlots = Math.max(1, sequentialScanLimitSlots);
    }

    @Override
    public synchronized void start() {
        if (running) {
            log.warn("SlotLeaderTimeTravelBlockProducer is already running");
            return;
        }

        ChainTip tip = chainState.getTip();
        if (tip != null && lastCheckedSlot < tip.getSlot()) {
            lastCheckedSlot = tip.getSlot();
        }
        BlockProducerHelper.resetEpochTrackingToSlot(lastCheckedSlot);

        running = true;
        scheduledTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                produceScheduled();
            } catch (Exception e) {
                log.error("Error in time-travel slot leader production", e);
            }
        }, blockTimeMillis, blockTimeMillis, TimeUnit.MILLISECONDS);

        log.info("SlotLeaderTimeTravelBlockProducer started: poolHash={}, blockTime={}ms, slotLength={}ms, sequential={}",
                poolHash, blockTimeMillis, slotLengthMillis, forceSequentialSlots);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        log.info("SlotLeaderTimeTravelBlockProducer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void resetToChainTip() {
        ChainTip tip = chainState.getTip();
        lastCheckedSlot = tip != null ? tip.getSlot() : -1;
        BlockProducerHelper.resetEpochTrackingToSlot(lastCheckedSlot);
        log.info("SlotLeaderTimeTravelBlockProducer reset to chain tip: slot={}", lastCheckedSlot);
    }

    public void setForceSequentialSlots(boolean forceSequentialSlots) {
        this.forceSequentialSlots = forceSequentialSlots;
    }

    /**
     * Catch-up entry point: scan from the last checked slot to {@code targetSlot} and forge every
     * eligible slot, or, with a backfill block interval above 1, only the first eligible slot at or
     * after each interval and after each epoch start (see {@link #setBackfillBlockIntervalSlots(int)}).
     */
    public synchronized int produceToSlot(long targetSlot) {
        return produceToSlot(targetSlot, false, true);
    }

    /**
     * Set how many slots apart the catch-up backfill places its blocks. 1 (default) forges every
     * eligible slot. Above 1, after each block the scan jumps to the earlier of {@code block + interval}
     * and the next epoch start, and forges the first eligible slot from there. Leadership checks are
     * skipped for the slots jumped over. Search cost depends on pool stake and eligibility.
     * Sparse catch-up fails if it cannot find a block before the forecast window expires.
     * Scheduled (wall-clock and sequential-scan) production is not affected.
     */
    public void setBackfillBlockIntervalSlots(int backfillBlockIntervalSlots) {
        if (backfillBlockIntervalSlots < 1) {
            throw new IllegalArgumentException("backfillBlockIntervalSlots must be at least 1, got "
                    + backfillBlockIntervalSlots);
        }
        this.backfillBlockIntervalSlots = backfillBlockIntervalSlots;
    }

    public int getBackfillBlockIntervalSlots() {
        return backfillBlockIntervalSlots;
    }

    /**
     * First slot worth a leadership check after a block at {@code lastBlockSlot}, given the interval:
     * {@code lastBlockSlot + interval}, pulled back to the next epoch start when that comes first, and
     * never before {@code slot} itself. Package-private for tests.
     */
    static long nextSparseCandidateSlot(long slot, long lastBlockSlot, int interval) {
        if (interval <= 1 || lastBlockSlot < 0) {
            return slot;
        }
        long earliest = lastBlockSlot + interval;
        long epochStart = BlockProducerHelper.firstSlotOfNextEpoch(lastBlockSlot);
        if (epochStart > lastBlockSlot && epochStart < earliest) {
            earliest = epochStart;
        }
        return Math.max(slot, earliest);
    }

    public long getLastCheckedSlot() {
        return lastCheckedSlot;
    }

    private synchronized void produceScheduled() {
        if (!running) {
            return;
        }

        if (forceSequentialSlots) {
            long targetSlot = Math.min(lastCheckedSlot + sequentialScanLimitSlots, calculateWallClockSlot());
            if (targetSlot <= lastCheckedSlot) {
                return;
            }
            long fromSlot = lastCheckedSlot + 1;
            int produced = produceToSlot(targetSlot, true, false);
            if (produced == 0) {
                log.debug("No eligible slot found while scanning slots {}..{}", fromSlot, targetSlot);
            }
            return;
        }

        long wallClockSlot = calculateWallClockSlot();
        produceToSlot(wallClockSlot, false, false);
    }

    private int produceToSlot(long targetSlot, boolean stopAfterFirstBlock, boolean sparse) {
        if (targetSlot <= lastCheckedSlot) {
            return 0;
        }

        int interval = sparse ? backfillBlockIntervalSlots : 1;
        if (interval > 1) {
            log.info("Sparse slot-leader backfill: first eligible slot every {} slots from slot {} to {}",
                    interval, lastCheckedSlot + 1, targetSlot);
        }

        int blocksProduced = 0;
        long leadershipChecks = 0;
        long started = System.nanoTime();
        long slot = lastCheckedSlot + 1;
        while (slot <= targetSlot) {
            if (interval > 1) {
                ChainTip tip = chainState.getTip();
                long candidate = nextSparseCandidateSlot(slot, tip != null ? tip.getSlot() : -1, interval);
                if (tip != null) {
                    long epochStart = epochNonceState.firstSlotOfEpoch(epochNonceState.epochForSlot(tip.getSlot()) + 1);
                    candidate = Math.max(slot, Math.min(candidate, epochStart));
                }
                if (candidate > targetSlot) {
                    // Nothing more to forge before the target: count the rest as checked.
                    lastCheckedSlot = targetSlot;
                    break;
                }
                slot = candidate;
                if (tip != null && slot - tip.getSlot() >= epochNonceState.forecastWindowSlots()) {
                    throw new IllegalStateException("Sparse backfill found no eligible block within the forecast window; "
                            + "reduce the interval or increase the devnet producer stake (tip=" + tip.getSlot()
                            + ", candidate=" + slot + ")");
                }
            }
            int epoch = epochNonceState.epochForSlot(slot);
            refreshStakeData(epoch);

            if (interval > 1 && sigma.signum() <= 0) {
                throw new IllegalStateException("Sparse backfill requires available positive producer stake in epoch " + epoch);
            }

            if (sigma.signum() > 0) {
                byte[] epochNonce = epochNonceState.previewEpochNonceForSlot(slot);
                if (epochNonce == null) {
                    if (interval > 1) {
                        throw new IllegalStateException("Sparse backfill requires an epoch nonce at slot " + slot);
                    }
                    log.warn("Epoch nonce not available, skipping leader check for slot {}", slot);
                } else {
                    leadershipChecks++;
                    BlockSigner.VrfSignResult vrfResult = slotLeaderCheck.checkAndProve(slot, epochNonce, sigma);
                    if (vrfResult != null) {
                        if (produceBlock(slot, vrfResult, !sparse)) {
                            blocksProduced++;
                            if (sparse && blocksProduced % 100 == 0) {
                                log.info("Slot-leader backfill progress: blocks={}, checks={}, slot={}, target={}",
                                        blocksProduced, leadershipChecks, slot, targetSlot);
                            }
                        }
                        if (stopAfterFirstBlock) {
                            lastCheckedSlot = slot;
                            break;
                        }
                    }
                }
            }

            lastCheckedSlot = slot;
            slot++;
        }

        if (sparse && blocksProduced > 0) {
            BlockProducerHelper.notifyServer(nodeServerSupplier.get());
        }
        if (sparse) {
            log.info("Slot-leader backfill complete: blocks={}, checks={}, processedSlot={}, elapsedMillis={}",
                    blocksProduced, leadershipChecks, lastCheckedSlot,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        return blocksProduced;
    }

    private void refreshStakeData(int epoch) {
        if (epoch == lastStakeEpoch) {
            return;
        }

        BigInteger poolStake = stakeDataProvider.getPoolStake(poolHash, epoch);
        BigInteger totalStake = stakeDataProvider.getTotalStake(epoch);
        if (poolStake == null || totalStake == null || totalStake.signum() == 0) {
            sigma = BigDecimal.ZERO;
            log.warn("Stake data unavailable for pool {} in epoch {} (poolStake={}, totalStake={})",
                    poolHash, epoch, poolStake, totalStake);
            return;
        }

        sigma = new BigDecimal(poolStake).divide(new BigDecimal(totalStake), MC);
        lastStakeEpoch = epoch;
        log.info("Time-travel stake data refreshed for epoch {}: poolStake={}, totalStake={}, sigma={}",
                epoch, poolStake, totalStake, sigma);
    }

    private boolean produceBlock(long slot, BlockSigner.VrfSignResult vrfResult, boolean notifyServer) {
        ChainTip tip = chainState.getTip();
        long blockNumber = tip != null ? tip.getBlockNumber() + 1 : 0;
        byte[] prevHash = tip != null ? tip.getBlockHash() : null;

        BlockProducerHelper.prepareEpochTransitionBeforeBlock(
                eventBus, slot, blockNumber, "slot-leader-time-travel");

        try {
            List<byte[]> txList = blockBuilder.fitTransactions(slot, transactions.drainForBlock());
            var result = blockBuilder.buildBlock(blockNumber, slot, prevHash, txList, vrfResult);
            BlockProducerHelper.storeProducedBlock(chainState, blockBuilder, result);

            log.debug("Slot-leader time-travel block #{} produced: slot={}, txs={}, hash={}",
                    blockNumber, slot, txList.size(), HexUtil.encodeHexString(result.blockHash()));

            BlockProducerHelper.publishEvent(eventBus, result, txList.size(), "slot-leader-time-travel");
            transactions.blockCandidatePublished();
            if (notifyServer) {
                BlockProducerHelper.notifyServer(nodeServerSupplier.get());
            }
            return true;
        } catch (UnfitBlockTransactionException e) {
            int removed;
            try {
                removed = transactions.invalidateSelectedTransaction(e.transactionHash());
            } finally {
                transactions.blockSelectionFailed();
            }
            log.warn("Discarded {} mempool transaction(s) after block resource rejection: {}",
                    removed, e.getMessage());
            return false;
        } catch (RuntimeException | Error e) {
            transactions.blockSelectionFailed();
            throw e;
        }
    }

    private long calculateWallClockSlot() {
        return (System.currentTimeMillis() - genesisTimestamp) / slotLengthMillis;
    }
}
