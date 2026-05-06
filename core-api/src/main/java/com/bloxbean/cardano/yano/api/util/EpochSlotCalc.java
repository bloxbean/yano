package com.bloxbean.cardano.yano.api.util;

/**
 * Dependency-free epoch/slot math utility.
 * <p>
 * Handles both Byron-era and post-Byron (Shelley+) slot-to-epoch conversion.
 * For networks with no Byron era (preview, sanchonet, devnets), set {@code firstNonByronSlot = 0}.
 * <p>
 * This class is intentionally free of runtime or genesis config dependencies so it can be
 * used from any module (core-api, ledger-state, runtime, app).
 */
public class EpochSlotCalc {

    private final long shelleyEpochLength;
    private final long byronSlotsPerEpoch;
    private final long firstNonByronSlot;
    private final int firstNonByronEpoch;

    /**
     * @param shelleyEpochLength slots per epoch in post-Byron eras (e.g. 432000 for mainnet, 86400 for preview)
     * @param byronSlotsPerEpoch slots per epoch in Byron era (= k * 10, e.g. 21600 for mainnet, 4320 for preview)
     * @param firstNonByronSlot  absolute slot where the first post-Byron era starts (0 if no Byron era).
     *                           Must be epoch-aligned: {@code firstNonByronSlot % byronSlotsPerEpoch == 0}
     *                           when {@code firstNonByronSlot > 0}.
     */
    public EpochSlotCalc(long shelleyEpochLength, long byronSlotsPerEpoch, long firstNonByronSlot) {
        if (shelleyEpochLength <= 0) {
            throw new IllegalArgumentException("shelleyEpochLength must be positive, got " + shelleyEpochLength);
        }
        if (byronSlotsPerEpoch <= 0) {
            throw new IllegalArgumentException("byronSlotsPerEpoch must be positive, got " + byronSlotsPerEpoch);
        }
        if (firstNonByronSlot > 0 && firstNonByronSlot % byronSlotsPerEpoch != 0) {
            throw new IllegalArgumentException(
                    "firstNonByronSlot (" + firstNonByronSlot + ") must be epoch-aligned "
                            + "(divisible by byronSlotsPerEpoch=" + byronSlotsPerEpoch + ")");
        }
        this.shelleyEpochLength = shelleyEpochLength;
        this.byronSlotsPerEpoch = byronSlotsPerEpoch;
        this.firstNonByronSlot = firstNonByronSlot;
        this.firstNonByronEpoch = firstNonByronSlot > 0
                ? (int) (firstNonByronSlot / byronSlotsPerEpoch)
                : 0;
    }

    /**
     * Convert an absolute slot to its epoch number.
     *
     * @param slot absolute slot
     * @return epoch number
     */
    public int slotToEpoch(long slot) {
        if (firstNonByronSlot == 0) {
            // No Byron era — pure post-Byron math
            return (int) (slot / shelleyEpochLength);
        }
        if (slot < firstNonByronSlot) {
            // Byron era
            return (int) (slot / byronSlotsPerEpoch);
        }
        // Post-Byron era
        return firstNonByronEpoch + (int) ((slot - firstNonByronSlot) / shelleyEpochLength);
    }

    /**
     * Convert an absolute slot to its slot-within-epoch (epoch slot).
     * Uses Byron modulo for slots before firstNonByronSlot, Shelley modulo after.
     *
     * @param slot absolute slot
     * @return slot within its epoch (0-based)
     */
    public int slotToEpochSlot(long slot) {
        if (firstNonByronSlot == 0) {
            return (int) (slot % shelleyEpochLength);
        }
        if (slot < firstNonByronSlot) {
            return (int) (slot % byronSlotsPerEpoch);
        }
        return (int) ((slot - firstNonByronSlot) % shelleyEpochLength);
    }

    /**
     * Convert an epoch + epoch-slot pair back to an absolute slot.
     * Uses Byron length for epochs before firstNonByronEpoch, Shelley length from firstNonByronEpoch onward.
     *
     * @param epoch    epoch number
     * @param epochSlot slot within the epoch (0-based)
     * @return absolute slot
     */
    public long epochSlotToAbsoluteSlot(int epoch, int epochSlot) {
        if (firstNonByronSlot == 0) {
            return (long) epoch * shelleyEpochLength + epochSlot;
        }
        if (epoch < firstNonByronEpoch) {
            return (long) epoch * byronSlotsPerEpoch + epochSlot;
        }
        long epochsAfterByron = epoch - firstNonByronEpoch;
        return firstNonByronSlot + epochsAfterByron * shelleyEpochLength + epochSlot;
    }

    /**
     * Get the absolute slot at the start of a given epoch.
     *
     * @param epoch epoch number
     * @return absolute slot at epoch start
     */
    public long epochToStartSlot(int epoch) {
        if (firstNonByronSlot == 0) {
            return (long) epoch * shelleyEpochLength;
        }
        if (epoch <= firstNonByronEpoch) {
            return (long) epoch * byronSlotsPerEpoch;
        }
        return firstNonByronSlot + (long) (epoch - firstNonByronEpoch) * shelleyEpochLength;
    }

    /**
     * @return the first epoch that uses post-Byron rules
     */
    public int firstNonByronEpoch() {
        return firstNonByronEpoch;
    }

    /**
     * @return the first slot of the first post-Byron era
     */
    public long firstNonByronSlot() {
        return firstNonByronSlot;
    }

    /**
     * @return slots per epoch in post-Byron eras
     */
    public long shelleyEpochLength() {
        return shelleyEpochLength;
    }

    /**
     * @return slots per epoch in Byron era
     */
    public long byronSlotsPerEpoch() {
        return byronSlotsPerEpoch;
    }
}
