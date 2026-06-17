package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.runtime.producer.ProducerSubsystem;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Devnet wall-clock-independent time advance operations.
 */
public final class DevnetTimeAdvanceService {
    public static final int DEFAULT_MAX_ADVANCE_SLOTS = 100_000;

    private final BooleanSupplier devMode;
    private final BooleanSupplier devnetProductionAvailable;
    private final ChainState chainState;
    private final ProducerSubsystem producerSubsystem;
    private final int maxAdvanceSlots;

    public DevnetTimeAdvanceService(BooleanSupplier devMode,
                                    BooleanSupplier devnetProductionAvailable,
                                    ChainState chainState,
                                    ProducerSubsystem producerSubsystem,
                                    int maxAdvanceSlots) {
        this.devMode = Objects.requireNonNull(devMode, "devMode");
        this.devnetProductionAvailable = Objects.requireNonNull(devnetProductionAvailable, "devnetProductionAvailable");
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.producerSubsystem = Objects.requireNonNull(producerSubsystem, "producerSubsystem");
        this.maxAdvanceSlots = maxAdvanceSlots;
    }

    public TimeAdvanceResult advanceBySlots(int slots) {
        requireDevMode();
        if (slots <= 0) {
            throw new IllegalArgumentException("Slots must be positive, got: " + slots);
        }
        return advanceBySlotCount(slots);
    }

    private TimeAdvanceResult advanceBySlotCount(long slots) {
        if (slots > maxAdvanceSlots) {
            throw new IllegalArgumentException("Cannot advance more than " + maxAdvanceSlots + " slots per request");
        }

        ChainTip currentTip = chainState.getTip();
        long currentSlot = currentTip != null ? currentTip.getSlot() : 0;
        long targetSlot = currentSlot + slots;

        boolean wasRunning = producerSubsystem.isRunning();
        if (wasRunning) {
            producerSubsystem.stop();
        }

        try {
            int blocksProduced = producerSubsystem.produceEmptyBlocksToSlot(targetSlot, "Time advance");
            ChainTip newTip = chainState.getTip();

            return new TimeAdvanceResult(
                    newTip != null ? newTip.getSlot() : 0,
                    newTip != null ? newTip.getBlockNumber() : 0,
                    blocksProduced);
        } finally {
            if (wasRunning) {
                producerSubsystem.start();
            }
        }
    }

    public TimeAdvanceResult advanceBySeconds(int seconds) {
        requireDevMode();
        if (seconds <= 0) {
            throw new IllegalArgumentException("Seconds must be positive, got: " + seconds);
        }

        int slotLengthMs = producerSubsystem.slotLengthMillis("Time advance");
        if (slotLengthMs <= 0) {
            throw new IllegalStateException("Slot length is not configured");
        }

        long slots = (long) seconds * 1000 / slotLengthMs;
        if (slots <= 0) {
            slots = 1;
        }

        return advanceBySlotCount(slots);
    }

    private void requireDevMode() {
        if (!devMode.getAsBoolean()) {
            throw new IllegalStateException("Time advance requires dev mode (yano.dev-mode=true)");
        }
        if (!devnetProductionAvailable.getAsBoolean()) {
            throw new IllegalStateException("Time advance requires block producer to be running");
        }
    }
}
