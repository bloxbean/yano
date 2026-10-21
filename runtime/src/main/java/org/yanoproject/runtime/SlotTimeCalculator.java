package org.yanoproject.runtime;

import org.yanoproject.runtime.blockproducer.SlotClock;
import org.yanoproject.runtime.chain.EraMetadataStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Era-aware slot-to-unix-time converter.
 * <p>
 * Uses the same formula as yaci-store's EraService:
 * <ul>
 *   <li>Byron: {@code networkStartTime + slot * byronSlotDuration}</li>
 *   <li>Shelley+: {@code shelleyEraStartTime + (slot - firstShelleySlot) * shelleySlotLength}</li>
 * </ul>
 * where {@code shelleyEraStartTime = networkStartTime + firstShelleySlot * byronSlotDuration}.
 * <p>
 * When {@code firstNonByronSlot == 0} (no Byron era), the formula degenerates to:
 * {@code networkStartTime + slot * shelleySlotLength}
 * <p>
 * The arithmetic is the millisecond {@link SlotClock} that forging uses, rounded to whole seconds.
 */
@Slf4j
public class SlotTimeCalculator {

    private final SlotClock clock;
    private final EraMetadataStore eraMetadataStore;
    private long firstNonByronSlot = -1;

    public SlotTimeCalculator(long networkStartTimeSec, long byronSlotDurationSec,
                              double shelleySlotLengthSec, EraMetadataStore eraMetadataStore) {
        this.eraMetadataStore = eraMetadataStore;
        // Until the first non-Byron slot is known, every slot is a Byron slot.
        this.clock = new SlotClock(networkStartTimeSec * 1000, byronSlotDurationSec * 1000,
                Math.round(shelleySlotLengthSec * 1000), () -> {
                    long firstShelley = resolveFirstNonByronSlot();
                    return firstShelley >= 0 ? firstShelley : Long.MAX_VALUE;
                });
    }

    /**
     * Convert a slot number to a Unix timestamp (seconds since epoch).
     *
     * @param slot the slot number
     * @return Unix timestamp in seconds, the slot start rounded to the nearest second
     */
    public long slotToUnixTime(long slot) {
        return Math.floorDiv(clock.slotStartMillis(slot) + 500, 1000);
    }

    /**
     * Set the first non-Byron slot directly (for testing or devnet initialization).
     */
    void setFirstNonByronSlot(long slot) {
        this.firstNonByronSlot = slot;
    }

    /**
     * Invalidate the cached first-non-Byron slot so it will be re-read from storage
     * on the next call. Should be called after snapshot restore.
     */
    public void invalidateCache() {
        this.firstNonByronSlot = -1;
    }

    private long resolveFirstNonByronSlot() {
        if (firstNonByronSlot >= 0) return firstNonByronSlot;
        if (eraMetadataStore != null) {
            var opt = eraMetadataStore.getFirstNonByronEraStartSlot();
            if (opt.isPresent()) {
                firstNonByronSlot = opt.getAsLong();
                return firstNonByronSlot;
            }
        }
        return -1;
    }
}
