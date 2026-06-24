package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.runtime.chain.MemPool;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;
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

    public synchronized int produceToSlot(long targetSlot) {
        return produceToSlot(targetSlot, false);
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
            int produced = produceToSlot(targetSlot, true);
            if (produced == 0) {
                log.debug("No eligible slot found while scanning slots {}..{}", fromSlot, targetSlot);
            }
            return;
        }

        long wallClockSlot = calculateWallClockSlot();
        produceToSlot(wallClockSlot, false);
    }

    private int produceToSlot(long targetSlot, boolean stopAfterFirstBlock) {
        if (targetSlot <= lastCheckedSlot) {
            return 0;
        }

        int blocksProduced = 0;
        long slot = lastCheckedSlot + 1;
        while (slot <= targetSlot) {
            lastCheckedSlot = slot;
            int epoch = epochNonceState.epochForSlot(slot);
            refreshStakeData(epoch);

            if (sigma.signum() > 0) {
                byte[] epochNonce = epochNonceState.previewEpochNonceForSlot(slot);
                if (epochNonce == null) {
                    log.warn("Epoch nonce not available, skipping leader check for slot {}", slot);
                } else {
                    BlockSigner.VrfSignResult vrfResult = slotLeaderCheck.checkAndProve(slot, epochNonce, sigma);
                    if (vrfResult != null) {
                        produceBlock(slot, vrfResult);
                        blocksProduced++;
                        if (stopAfterFirstBlock) {
                            break;
                        }
                    }
                }
            }

            slot++;
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

    private void produceBlock(long slot, BlockSigner.VrfSignResult vrfResult) {
        ChainTip tip = chainState.getTip();
        long blockNumber = tip != null ? tip.getBlockNumber() + 1 : 0;
        byte[] prevHash = tip != null ? tip.getBlockHash() : null;

        BlockProducerHelper.prepareEpochTransitionBeforeBlock(
                eventBus, slot, blockNumber, "slot-leader-time-travel");

        List<byte[]> txList = transactions.drainForBlock();

        var result = blockBuilder.buildBlock(blockNumber, slot, prevHash, txList, vrfResult);
        BlockProducerHelper.storeProducedBlock(chainState, blockBuilder, result);

        log.info("Slot-leader time-travel block #{} produced: slot={}, txs={}, hash={}",
                blockNumber, slot, txList.size(), HexUtil.encodeHexString(result.blockHash()));

        BlockProducerHelper.publishEvent(eventBus, result, txList.size(), "slot-leader-time-travel");
        BlockProducerHelper.notifyServer(nodeServerSupplier.get());
    }

    private long calculateWallClockSlot() {
        return (System.currentTimeMillis() - genesisTimestamp) / slotLengthMillis;
    }
}
