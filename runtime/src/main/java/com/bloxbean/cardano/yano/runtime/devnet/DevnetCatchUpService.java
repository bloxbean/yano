package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;
import com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystem;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Devnet catch-up operations that move time-travel producers back to wall-clock mode.
 */
@Slf4j
public final class DevnetCatchUpService {
    private final BooleanSupplier devMode;
    private final BooleanSupplier pastTimeTravelSlotLeaderMode;
    private final ChainState chainState;
    private final ProducerSubsystem producerSubsystem;
    private final LongSupplier resolvedGenesisTimestampMillis;
    private final IntSupplier slotLengthMillis;
    private final LongSupplier currentTimeMillis;
    private final MaintenanceFailureReporter failureReporter;

    public DevnetCatchUpService(BooleanSupplier devMode,
                                BooleanSupplier pastTimeTravelSlotLeaderMode,
                                ChainState chainState,
                                ProducerSubsystem producerSubsystem,
                                LongSupplier resolvedGenesisTimestampMillis,
                                IntSupplier slotLengthMillis,
                                LongSupplier currentTimeMillis) {
        this(devMode,
                pastTimeTravelSlotLeaderMode,
                chainState,
                producerSubsystem,
                resolvedGenesisTimestampMillis,
                slotLengthMillis,
                currentTimeMillis,
                MaintenanceFailureReporter.noop());
    }

    public DevnetCatchUpService(BooleanSupplier devMode,
                                BooleanSupplier pastTimeTravelSlotLeaderMode,
                                ChainState chainState,
                                ProducerSubsystem producerSubsystem,
                                LongSupplier resolvedGenesisTimestampMillis,
                                IntSupplier slotLengthMillis,
                                LongSupplier currentTimeMillis,
                                MaintenanceFailureReporter failureReporter) {
        this.devMode = Objects.requireNonNull(devMode, "devMode");
        this.pastTimeTravelSlotLeaderMode = Objects.requireNonNull(
                pastTimeTravelSlotLeaderMode, "pastTimeTravelSlotLeaderMode");
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.producerSubsystem = Objects.requireNonNull(producerSubsystem, "producerSubsystem");
        this.resolvedGenesisTimestampMillis = Objects.requireNonNull(
                resolvedGenesisTimestampMillis, "resolvedGenesisTimestampMillis");
        this.slotLengthMillis = Objects.requireNonNull(slotLengthMillis, "slotLengthMillis");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        this.failureReporter = failureReporter != null ? failureReporter : MaintenanceFailureReporter.noop();
    }

    public TimeAdvanceResult catchUpToWallClock() {
        if (!devMode.getAsBoolean()) {
            throw new IllegalStateException("Catch-up requires dev mode");
        }
        if (pastTimeTravelSlotLeaderMode.getAsBoolean()) {
            return catchUpSlotLeaderToWallClock();
        }
        if (!producerSubsystem.hasDevnetProduction()) {
            throw new IllegalStateException("Catch-up requires block producer to be running");
        }

        long wallClockSlot = wallClockSlot();
        ChainTip currentTip = chainState.getTip();
        long currentSlot = currentTip != null ? currentTip.getSlot() : 0;
        long slotsToAdvance = wallClockSlot - currentSlot;

        if (slotsToAdvance <= 0) {
            log.info("Already at or past wall-clock slot {} (tip={}), nothing to catch up", wallClockSlot, currentSlot);
            return new TimeAdvanceResult(currentSlot,
                    currentTip != null ? currentTip.getBlockNumber() : 0, 0);
        }

        log.info("Catching up to wall-clock: {} slots (current={}, target={})",
                slotsToAdvance, currentSlot, wallClockSlot);

        boolean wasRunning = producerSubsystem.isRunning();
        boolean mutationStarted = false;

        try {
            mutationStarted = true;
            if (wasRunning) {
                producerSubsystem.stop();
            }
            int blocksProduced = producerSubsystem.produceEmptyBlocksToSlot(wallClockSlot, "Catch-up");
            producerSubsystem.setForceSequentialSlots(false, "Catch-up");
            ChainTip newTip = chainState.getTip();

            log.info("Catch-up complete: {} blocks produced, new tip slot={}", blocksProduced,
                    newTip != null ? newTip.getSlot() : 0);

            return new TimeAdvanceResult(
                    newTip != null ? newTip.getSlot() : 0,
                    newTip != null ? newTip.getBlockNumber() : 0,
                    blocksProduced);
        } catch (RuntimeException | Error e) {
            if (mutationStarted) {
                markCatchUpDegraded(e);
            }
            throw e;
        } finally {
            if (wasRunning) {
                restartProducerAfterCatchUp();
            }
        }
    }

    private TimeAdvanceResult catchUpSlotLeaderToWallClock() {
        if (producerSubsystem.modeOrNull() != ProducerMode.SLOT_LEADER_TIME_TRAVEL) {
            throw new IllegalStateException("Catch-up requires past-time-travel slot-leader producer to be running");
        }

        long wallClockSlot = wallClockSlot();
        long currentSlot = producerSubsystem.lastCheckedSlot("Slot-leader catch-up");
        long slotsToAdvance = wallClockSlot - currentSlot;
        ChainTip currentTip = chainState.getTip();

        if (slotsToAdvance <= 0) {
            log.info("Already at or past wall-clock slot {} (checked={}), nothing to catch up",
                    wallClockSlot, currentSlot);
            return new TimeAdvanceResult(
                    currentTip != null ? currentTip.getSlot() : Math.max(currentSlot, 0),
                    currentTip != null ? currentTip.getBlockNumber() : 0,
                    0);
        }

        log.info("Catching up to wall-clock with slot-leader checks: {} slots (checked={}, target={})",
                slotsToAdvance, currentSlot, wallClockSlot);

        boolean wasRunning = producerSubsystem.isRunning();
        boolean mutationStarted = false;

        try {
            mutationStarted = true;
            if (wasRunning) {
                producerSubsystem.stop();
            }
            int blocksProduced = producerSubsystem.produceLeaderBlocksToSlot(wallClockSlot, "Slot-leader catch-up");
            producerSubsystem.setForceSequentialSlots(false, "Slot-leader catch-up");
            ChainTip newTip = chainState.getTip();
            long lastCheckedSlot = producerSubsystem.lastCheckedSlot("Slot-leader catch-up");

            log.info("Slot-leader catch-up complete: {} blocks produced, checked slot={}, tip slot={}",
                    blocksProduced, lastCheckedSlot,
                    newTip != null ? newTip.getSlot() : 0);

            return new TimeAdvanceResult(
                    newTip != null ? newTip.getSlot() : lastCheckedSlot,
                    newTip != null ? newTip.getBlockNumber() : 0,
                    blocksProduced);
        } catch (RuntimeException | Error e) {
            if (mutationStarted) {
                markCatchUpDegraded(e);
            }
            throw e;
        } finally {
            if (wasRunning) {
                restartProducerAfterCatchUp();
            }
        }
    }

    private void restartProducerAfterCatchUp() {
        try {
            producerSubsystem.start();
        } catch (RuntimeException | Error e) {
            failureReporter.markDegraded(
                    "devnet catch-up",
                    "Devnet catch-up could not restart the producer; restart required",
                    e);
            throw e;
        }
    }

    private void markCatchUpDegraded(Throwable cause) {
        failureReporter.markDegraded(
                "devnet catch-up",
                "Devnet catch-up failed after producer or chain state mutation started; restart required",
                cause);
    }

    private long wallClockSlot() {
        int slotLength = slotLengthMillis.getAsInt();
        if (slotLength <= 0) {
            throw new IllegalStateException("Catch-up requires positive slot length");
        }
        return (currentTimeMillis.getAsLong() - resolvedGenesisTimestampMillis.getAsLong())
                / slotLength;
    }
}
