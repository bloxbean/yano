package com.bloxbean.cardano.yano.testkit.external;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class YanoExternalSyncAssertionsTest {
    @Test
    void assertTipsSyncedAllowsSlotsWithinTolerance() {
        YanoExternalSyncAssertions.assertTipsSynced(100, 104, 4);
        YanoExternalSyncAssertions.assertTipsSynced(104, 100, 4);
    }

    @Test
    void assertTipsSyncedFailsWhenDifferenceExceedsTolerance() {
        assertThrows(AssertionError.class,
                () -> YanoExternalSyncAssertions.assertTipsSynced(100, 106, 5));
    }

    @Test
    void assertTipsSyncedRejectsNegativeTolerance() {
        assertThrows(IllegalArgumentException.class,
                () -> YanoExternalSyncAssertions.assertTipsSynced(100, 100, -1));
    }

    @Test
    void assertTipsSyncedRejectsUnsyncedSentinelSlots() {
        assertThrows(AssertionError.class,
                () -> YanoExternalSyncAssertions.assertTipsSynced(100, -1, 200));
    }
}
