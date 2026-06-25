package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;

import java.util.Objects;

/**
 * Deterministic devnet time helpers backed by {@link DevnetControl}.
 */
public final class YanoTime {
    private final DevnetControl devnet;
    private final YanoQueries queries;

    YanoTime(DevnetControl devnet, YanoQueries queries) {
        this.devnet = Objects.requireNonNull(devnet, "devnet");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /**
     * Advances devnet time by a number of slots.
     *
     * @param slots slots to advance
     * @return advance result
     */
    public TimeAdvanceResult advanceSlots(int slots) {
        requirePositive(slots, "slots");
        return devnet.advanceTimeBySlots(slots);
    }

    /**
     * Advances devnet time by a number of seconds.
     *
     * @param seconds seconds to advance
     * @return advance result
     */
    public TimeAdvanceResult advanceSeconds(int seconds) {
        requirePositive(seconds, "seconds");
        return devnet.advanceTimeBySeconds(seconds);
    }

    /**
     * Advances devnet time until the current slot is at least the target slot.
     *
     * @param targetSlot target slot
     * @return advance result
     */
    public TimeAdvanceResult advanceToSlot(long targetSlot) {
        long currentSlot = queries.currentSlot();
        if (targetSlot <= currentSlot) {
            return new TimeAdvanceResult(currentSlot, queries.currentBlockNumber(), 0);
        }
        return devnet.advanceTimeUntilSlot(targetSlot);
    }

    /**
     * Advances devnet time until the current epoch is at least the target epoch.
     *
     * @param targetEpoch target epoch
     * @return advance result
     */
    public TimeAdvanceResult advanceToEpoch(long targetEpoch) {
        requireNonNegative(targetEpoch, "targetEpoch");
        long currentEpoch = queries.currentEpoch();
        if (targetEpoch <= currentEpoch) {
            return new TimeAdvanceResult(queries.currentSlot(), queries.currentBlockNumber(), 0);
        }

        return advanceToSlot(queries.epochStartSlot(targetEpoch));
    }

    /**
     * Advances devnet time across the next epoch boundary.
     *
     * @return advance result
     */
    public TimeAdvanceResult crossEpochBoundary() {
        return advanceToEpoch(queries.currentEpoch() + 1);
    }

    /**
     * Shifts genesis in past-time-travel mode and starts the producer.
     *
     * @param epochs number of epochs to shift
     * @return shifted genesis timestamp
     */
    public long shiftGenesisAndStartProducer(int epochs) {
        requirePositive(epochs, "epochs");
        return devnet.shiftGenesisAndStartProducer(epochs);
    }

    /**
     * Produces blocks until the devnet catches up to wall-clock time.
     *
     * @return advance result
     */
    public TimeAdvanceResult catchUpToWallClock() {
        return devnet.catchUpToWallClock();
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
