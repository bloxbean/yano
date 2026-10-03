package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.kes.OpCert;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.tx.BlockTransactionSelector;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * Slot-leader block producer: on each slot it checks Ouroboros Praos leader eligibility from the pool's relative
 * stake and forges a block only when elected.
 *
 * <p>Safety rules: it checks only once the chain is caught up ({@link ForgingReadiness}), never forges a slot at or
 * before the chain tip, and never forges a slot at or before the last slot it forged, which is persisted
 * ({@link ForgedSlotStore}) so that neither a restart nor a rollback can produce a second block for a slot. It
 * stores a block only if the tip it was built on is still the tip; an upstream block that competes with a stored
 * forged block wins and rolls it back ({@code HeaderSyncManager}).</p>
 */
@Slf4j
public class SlotLeaderBlockProducer implements BlockProducerService {

    /** Slots to wait before asking the stake source again after it had no data for the epoch. */
    static final long STAKE_RETRY_SLOTS = 100;

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
    private final SlotClock slotClock;
    private final ForgingReadiness readiness;
    private final ForgedSlotStore forgedSlotStore;

    // Runtime state
    private ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private long run; // incremented by each start(), so a tick of an earlier run never reschedules
    private long lastCheckedSlot = -1;
    private long lastForgedSlot = -1;
    private int lastStakeEpoch = -1;
    private long nextStakeAttemptSlot = -1;
    private BigDecimal sigma = BigDecimal.ZERO;

    public SlotLeaderBlockProducer(
            ChainState chainState, BlockTransactionSelector transactions, Supplier<NodeServer> nodeServerSupplier,
            EventBus eventBus, ScheduledExecutorService scheduler,
            SignedBlockBuilder blockBuilder, EpochNonceState epochNonceState,
            SlotLeaderCheck slotLeaderCheck, StakeDataProvider stakeDataProvider,
            String poolHash, SlotClock slotClock, ForgingReadiness readiness) {
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
        this.slotClock = Objects.requireNonNull(slotClock, "slotClock");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.forgedSlotStore = chainState instanceof ForgedSlotStore store ? store : null;
    }

    @Override
    public synchronized void start() {
        if (running) {
            log.warn("SlotLeaderBlockProducer is already running");
            return;
        }
        ChainTip tip = chainState.getTip();
        long tipSlot = tip != null ? tip.getSlot() : -1;
        if (forgedSlotStore != null) {
            lastForgedSlot = Math.max(lastForgedSlot, forgedSlotStore.getLastForgedSlot());
        }
        lastCheckedSlot = Math.max(lastCheckedSlot, Math.max(lastForgedSlot, tipSlot));
        BlockProducerHelper.resetEpochTrackingToSlot(tipSlot);
        running = true;
        scheduleNextSlot(++run);

        log.info("SlotLeaderBlockProducer started: poolHash={}, slotLength={}ms, lastForgedSlot={}, tipSlot={}",
                poolHash, slotClock.slotMillis(), lastForgedSlot, tipSlot);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        log.info("SlotLeaderBlockProducer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void resetToChainTip() {
        ChainTip tip = chainState.getTip();
        if (tip != null) {
            lastCheckedSlot = tip.getSlot();
            BlockProducerHelper.resetEpochTrackingToSlot(tip.getSlot());
            log.info("SlotLeaderBlockProducer reset to chain tip: slot={}", tip.getSlot());
        } else {
            BlockProducerHelper.resetEpochTrackingToSlot(-1);
        }
    }

    /** Runs the check for the current slot, then waits for the start of the next slot. */
    private void onSlotTick(long tickRun) {
        try {
            checkAndProduceBlock();
        } catch (Exception e) {
            log.error("Error in slot leader check", e);
        } finally {
            synchronized (this) {
                if (running && tickRun == run) {
                    scheduleNextSlot(tickRun);
                }
            }
        }
    }

    private void scheduleNextSlot(long tickRun) {
        long now = System.currentTimeMillis();
        long delay = Math.max(1, slotClock.slotStartMillis(slotClock.slotAt(now) + 1) - now);
        scheduledTask = scheduler.schedule(() -> onSlotTick(tickRun), delay, TimeUnit.MILLISECONDS);
    }

    synchronized void checkAndProduceBlock() {
        checkSlot(slotClock.slotAt(System.currentTimeMillis()));
    }

    /** The leader check for {@code currentSlot}, the wall-clock slot. */
    synchronized void checkSlot(long currentSlot) {
        if (!running || currentSlot <= lastCheckedSlot) {
            return;
        }
        lastCheckedSlot = currentSlot;

        ChainTip tip = chainState.getTip();
        if (!readiness.isCaughtUp(tip)) {
            if (currentSlot % 100 == 0) { // Log periodically, not every slot
                log.info("Not caught up with upstream (tip slot {}), skipping leader check for slot {}",
                        tip != null ? tip.getSlot() : null, currentSlot);
            }
            return;
        }
        if (tip == null || currentSlot <= tip.getSlot() || currentSlot <= lastForgedSlot) {
            return;
        }

        int currentEpoch = epochNonceState.epochForSlot(currentSlot);
        refreshStakeData(currentEpoch, currentSlot);
        if (sigma.signum() == 0) {
            if (currentSlot % 100 == 0) {
                log.debug("No stake for pool {} in epoch {}, skipping leader check", poolHash, currentEpoch);
            }
            return;
        }

        // Preview epoch nonce without mutating shared nonce state. The block builder applies
        // the epoch transition only if this slot actually produces a block.
        byte[] epochNonce = epochNonceState.previewEpochNonceForSlot(currentSlot);
        if (epochNonce == null) {
            log.warn("Epoch nonce not available, skipping leader check for slot {}", currentSlot);
            return;
        }

        BlockSigner.VrfSignResult vrfResult = slotLeaderCheck.checkAndProve(currentSlot, epochNonce, sigma);
        if (vrfResult == null) {
            return; // Not a leader for this slot
        }

        log.info("SLOT LEADER! Elected for slot {} (epoch {})", currentSlot, currentEpoch);
        try {
            produceBlock(currentSlot, vrfResult, tip);
        } catch (Exception e) {
            log.error("Failed to produce block for slot {}", currentSlot, e);
        }
    }

    /**
     * Loads the epoch's relative stake. When the stake source has nothing for the epoch the stake is zero (a stale
     * value from another epoch would claim leadership the network rejects) and the source is asked again after
     * {@link #STAKE_RETRY_SLOTS}.
     */
    private void refreshStakeData(int epoch, long slot) {
        if (epoch == lastStakeEpoch || slot < nextStakeAttemptSlot) {
            return;
        }

        BigInteger poolStake = stakeDataProvider.getPoolStake(poolHash, epoch);
        BigInteger totalStake = stakeDataProvider.getTotalStake(epoch);
        if (poolStake == null || totalStake == null || totalStake.signum() == 0) {
            sigma = BigDecimal.ZERO;
            nextStakeAttemptSlot = slot + STAKE_RETRY_SLOTS;
            log.info("Stake data not available for pool {} in epoch {} (poolStake={}, totalStake={})",
                    poolHash, epoch, poolStake, totalStake);
            return;
        }

        sigma = SlotLeaderCheck.relativeStake(poolStake, totalStake);
        lastStakeEpoch = epoch;
        log.info("Stake data refreshed for epoch {}: poolStake={}, totalStake={}, sigma={}",
                epoch, poolStake, totalStake, sigma);
    }

    private void produceBlock(long slot, BlockSigner.VrfSignResult vrfResult, ChainTip tip) {
        long blockNumber = tip.getBlockNumber() + 1;
        byte[] prevHash = tip.getBlockHash();

        // ADR-056: boundary section, then block selection, then the store-and-apply section.
        BlockProducerHelper.prepareEpochTransitionInWriteSection(
                chainState, eventBus, slot, blockNumber, "slot-leader-block-producer");

        try {
            List<byte[]> txList = blockBuilder.fitTransactions(slot, transactions.drainForBlock(slot));
            var result = blockBuilder.buildBlock(blockNumber, slot, prevHash, txList, vrfResult);

            try (var section = BlockProducerHelper.enterCanonicalWrite(chainState)) {
                BlockProducerHelper.requireCurrentSelection(transactions, section, blockBuilder, slot);
                storeOnUnchangedTip(section, slot, tip, result);

                log.info("Block #{} produced: slot={}, txs={}, hash={}",
                        blockNumber, slot, txList.size(), HexUtil.encodeHexString(result.blockHash()));

                BlockProducerHelper.publishEvent(eventBus, result, txList.size(), "slot-leader-block-producer");
            }
            transactions.blockCandidatePublished();
            BlockProducerHelper.notifyServer(nodeServerSupplier.get());
        } catch (UnfitBlockTransactionException e) {
            int removed;
            try {
                removed = transactions.invalidateSelectedTransaction(e.transactionHash());
            } finally {
                transactions.blockSelectionFailed();
            }
            log.warn("Discarded {} mempool transaction(s) after block resource rejection: {}",
                    removed, e.getMessage());
        } catch (StaleBlockSelectionException e) {
            transactions.blockSelectionFailed();
            log.info(e.getMessage());
        } catch (RuntimeException | Error e) {
            transactions.blockSelectionFailed();
            throw e;
        }
    }

    /**
     * Stores the forged block if the chain has not moved since {@code tip} was read and is still caught up. An
     * upstream header stored meanwhile would otherwise sit beside the forged block at the same height. The re-check
     * and the store hold the chain-extension lock, which the header store holds while it checks, rolls back and
     * stores; it is only tried here, so a header store waiting for a rollback (which enters this write section)
     * never waits on this producer ({@link CanonicalStateGate#chainExtensionLock()}).
     */
    private void storeOnUnchangedTip(CanonicalStateGate.WriteSection section, long slot, ChainTip tip,
                                     DevnetBlockBuilder.BlockBuildResult result) {
        Lock extension = CanonicalStateGate.of(chainState).chainExtensionLock();
        if (!extension.tryLock()) {
            throw BlockProducerHelper.discardBuiltBlock(section, blockBuilder, slot);
        }
        try {
            ChainTip current = chainState.getTip();
            if (current == null || !Arrays.equals(current.getBlockHash(), tip.getBlockHash())
                    || !readiness.isCaughtUp(current)) {
                throw BlockProducerHelper.discardBuiltBlock(section, blockBuilder, slot);
            }
            recordForgedSlot(section, slot);
            BlockProducerHelper.storeProducedBlock(chainState, blockBuilder, result);
        } finally {
            extension.unlock();
        }
    }

    /** Persists {@code slot} as forged before its block is stored, so it is durable before it can be served. */
    private void recordForgedSlot(CanonicalStateGate.WriteSection section, long slot) {
        if (forgedSlotStore != null) {
            try {
                forgedSlotStore.storeLastForgedSlot(slot);
            } catch (RuntimeException e) {
                section.markUnchanged();
                blockBuilder.rollbackPendingNonceState();
                throw e;
            }
        }
        lastForgedSlot = slot;
    }

    /**
     * Derive the pool hash from an operational certificate.
     * Pool hash = blake2b_224(coldVkey).
     *
     * @param opCert the operational certificate
     * @return hex-encoded pool hash (28 bytes / 56 hex chars)
     */
    public static String derivePoolHash(OpCert opCert) {
        byte[] coldVkey = opCert.getColdVkey();
        byte[] hash = Blake2bUtil.blake2bHash224(coldVkey);
        return HexUtil.encodeHexString(hash);
    }
}
