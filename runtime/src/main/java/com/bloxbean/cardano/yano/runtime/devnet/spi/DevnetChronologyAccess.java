package com.bloxbean.cardano.yano.runtime.devnet.spi;

/**
 * Devnet slot/time query port backed by the runtime chronology subsystem.
 */
public interface DevnetChronologyAccess {
    /**
     * Computes the current wall-clock slot from runtime genesis and slot length.
     *
     * @return current wall-clock slot
     */
    long currentWallClockSlot();

    /**
     * Returns the configured slot length.
     *
     * @return slot length in milliseconds
     */
    long slotLengthMillis();

    /**
     * Returns the configured epoch length.
     *
     * @return epoch length in slots
     */
    long epochLength();

    /**
     * Converts a slot to Unix time.
     *
     * @param slot slot number
     * @return Unix time in milliseconds, or 0 if chronology is unavailable
     */
    long slotToUnixTime(long slot);

    /**
     * Invalidates cached slot-time calculations.
     */
    void invalidateCaches();
}
