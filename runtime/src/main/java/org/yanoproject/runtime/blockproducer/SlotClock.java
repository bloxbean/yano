package org.yanoproject.runtime.blockproducer;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Wall-clock slot arithmetic for forging, in milliseconds and aware of a Byron prefix: Byron slots of
 * {@code byronSlotMillis} run from {@code systemStartMillis} up to the first Shelley slot, then slots of
 * {@code slotMillis}. {@link org.yanoproject.runtime.SlotTimeCalculator} rounds it to whole seconds. Without a Byron
 * prefix (first Shelley slot 0) it is {@code (now - systemStart) / slotMillis}.
 *
 * @param systemStartMillis the network start (Shelley genesis {@code systemStart}, which equals the Byron start)
 * @param byronSlotMillis   the Byron slot length
 * @param slotMillis        the Shelley-era slot length
 * @param firstShelleySlot  the first Shelley slot (read on each call: it is known once the node has seen Shelley)
 */
public record SlotClock(long systemStartMillis, long byronSlotMillis, long slotMillis,
                        LongSupplier firstShelleySlot) {

    public SlotClock {
        if (byronSlotMillis <= 0 || slotMillis <= 0) {
            throw new IllegalArgumentException("slot lengths must be positive");
        }
        Objects.requireNonNull(firstShelleySlot, "firstShelleySlot");
    }

    /** A clock without a Byron prefix. */
    public static SlotClock shelleyOnly(long systemStartMillis, long slotMillis) {
        return new SlotClock(systemStartMillis, slotMillis, slotMillis, () -> 0L);
    }

    /** @return the slot containing {@code nowMillis}, or -1 before the system start */
    public long slotAt(long nowMillis) {
        long elapsed = nowMillis - systemStartMillis;
        if (elapsed < 0) {
            return -1;
        }
        long shelleyStart = firstShelleySlot.getAsLong();
        long byronSpan = shelleyStart * byronSlotMillis;
        if (elapsed < byronSpan) {
            return elapsed / byronSlotMillis;
        }
        return shelleyStart + (elapsed - byronSpan) / slotMillis;
    }

    /** @return the wall-clock time at which {@code slot} starts */
    public long slotStartMillis(long slot) {
        long shelleyStart = firstShelleySlot.getAsLong();
        if (slot < shelleyStart) {
            return systemStartMillis + slot * byronSlotMillis;
        }
        return systemStartMillis + shelleyStart * byronSlotMillis + (slot - shelleyStart) * slotMillis;
    }
}
