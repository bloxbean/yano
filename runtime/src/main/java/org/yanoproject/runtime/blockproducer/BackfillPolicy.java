package org.yanoproject.runtime.blockproducer;

/** Slot-based spacing for opt-in sparse Shelley devnet history. */
public final class BackfillPolicy {
    private BackfillPolicy() {
    }

    public static long forecastWindowSlots(long securityParam, double activeSlotsCoeff) {
        if (securityParam <= 0 || !Double.isFinite(activeSlotsCoeff)
                || activeSlotsCoeff <= 0 || activeSlotsCoeff > 1) {
            throw new IllegalArgumentException("Backfill requires positive k and 0 < f <= 1");
        }
        double window = Math.floor(3.0 * securityParam / activeSlotsCoeff);
        if (window >= Long.MAX_VALUE) {
            throw new IllegalArgumentException("Backfill forecast window exceeds supported slot range");
        }
        return (long) window;
    }

    /** Zero selects automatic spacing; one preserves dense production. */
    public static int resolveInterval(int requested, long securityParam, double activeSlotsCoeff,
                                      boolean slotLeader) {
        if (requested < 0) {
            throw new IllegalArgumentException("Backfill interval must be non-negative");
        }
        long window = forecastWindowSlots(securityParam, activeSlotsCoeff);
        if (requested >= window) {
            throw new IllegalArgumentException("Backfill interval must be below forecast window of " + window + " slots");
        }
        if (requested > 0) {
            return requested;
        }
        // Eligibility searches need headroom. Empty-block production has no such search.
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, slotLeader ? window / 2 : window - 1));
    }
}
