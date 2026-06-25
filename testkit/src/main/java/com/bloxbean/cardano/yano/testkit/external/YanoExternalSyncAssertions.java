package com.bloxbean.cardano.yano.testkit.external;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assertions for opt-in external process compatibility tests.
 */
public final class YanoExternalSyncAssertions {
    private static final Logger log = LoggerFactory.getLogger(YanoExternalSyncAssertions.class);

    private YanoExternalSyncAssertions() {
    }

    /**
     * Asserts that Yano and Haskell cardano-node tips are within a slot tolerance.
     *
     * @param yano Yano app process
     * @param haskell Haskell cardano-node process
     * @param toleranceSlots accepted slot difference
     * @throws Exception if Yano tip cannot be queried
     */
    public static void assertTipsSynced(YanoAppProcess yano,
                                        HaskellCardanoNodeProcess haskell,
                                        int toleranceSlots) throws Exception {
        JsonNode yanoTip = yano.tip();
        long yanoSlot = yanoTip.get("slot").asLong();
        long haskellSlot = haskell.latestSyncedSlot();
        assertTipsSynced(yanoSlot, haskellSlot, toleranceSlots);
    }

    /**
     * Asserts that two tip slots are within a slot tolerance.
     *
     * @param yanoSlot Yano slot
     * @param haskellSlot Haskell cardano-node slot
     * @param toleranceSlots accepted slot difference
     */
    public static void assertTipsSynced(long yanoSlot, long haskellSlot, int toleranceSlots) {
        if (toleranceSlots < 0) {
            throw new IllegalArgumentException("toleranceSlots must be greater than or equal to 0");
        }
        if (yanoSlot < 0 || haskellSlot < 0) {
            throw new AssertionError("Cannot compare unsynced tips: Yano slot="
                    + yanoSlot + ", Haskell slot=" + haskellSlot);
        }
        long difference = Math.abs(yanoSlot - haskellSlot);
        log.info("Tip comparison: Yano slot={}, Haskell slot={}, difference={}",
                yanoSlot, haskellSlot, difference);
        if (difference > toleranceSlots) {
            throw new AssertionError("Slot difference between Yano (" + yanoSlot + ") and Haskell ("
                    + haskellSlot + ") exceeds tolerance of " + toleranceSlots);
        }
    }
}
