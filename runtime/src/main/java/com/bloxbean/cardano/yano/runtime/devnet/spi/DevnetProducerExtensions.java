package com.bloxbean.cardano.yano.runtime.devnet.spi;

import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;

import java.util.Optional;

/**
 * Devnet producer and time-control operations that extend the public producer
 * lifecycle role.
 */
public interface DevnetProducerExtensions {
    /**
     * Returns whether any block production strategy is installed.
     *
     * @return true when producer operations are available
     */
    boolean isAvailable();

    /**
     * Returns the active producer mode, if installed.
     *
     * @return producer mode
     */
    Optional<ProducerMode> mode();

    /**
     * Produces blocks by advancing a number of slots.
     *
     * @param slots positive slot count
     * @return resulting chain tip and block count
     */
    TimeAdvanceResult advanceBySlots(int slots);

    /**
     * Produces blocks until a target slot.
     *
     * @param targetSlot target slot
     * @return resulting chain tip and block count
     */
    TimeAdvanceResult advanceUntilSlot(long targetSlot);

    /**
     * Produces blocks by advancing wall-clock seconds.
     *
     * @param seconds positive second count
     * @return resulting chain tip and block count
     */
    TimeAdvanceResult advanceBySeconds(int seconds);

    /**
     * Catches a time-travel devnet producer up to wall-clock time.
     *
     * @return resulting chain tip and block count
     */
    TimeAdvanceResult catchUpToWallClock();

    /**
     * Shifts genesis backwards and starts a deferred time-travel producer.
     *
     * @param epochs positive epoch count
     * @return shift in milliseconds
     */
    long shiftGenesisAndStartProducer(int epochs);
}
