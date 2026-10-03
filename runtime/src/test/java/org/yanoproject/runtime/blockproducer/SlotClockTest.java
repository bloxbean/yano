package org.yanoproject.runtime.blockproducer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlotClockTest {

    // Preprod: systemStart 2022-06-01T00:00:00Z, 20 s Byron slots for 4 Byron epochs (86,400 slots), then 1 s slots.
    private static final long PREPROD_START = 1_654_041_600_000L;
    private static final SlotClock PREPROD = new SlotClock(PREPROD_START, 20_000, 1_000, () -> 86_400L);

    @Test
    void byronSlotsAreTwentySeconds() {
        assertThat(PREPROD.slotAt(PREPROD_START)).isZero();
        assertThat(PREPROD.slotAt(PREPROD_START + 19_999)).isZero();
        assertThat(PREPROD.slotAt(PREPROD_START + 20_000)).isEqualTo(1);
        assertThat(PREPROD.slotAt(PREPROD_START + 86_399L * 20_000)).isEqualTo(86_399);
    }

    @Test
    void shelleySlotsStartAfterTheByronPrefix() {
        long shelleyStart = PREPROD_START + 86_400L * 20_000;

        assertThat(PREPROD.slotAt(shelleyStart)).isEqualTo(86_400);
        assertThat(PREPROD.slotAt(shelleyStart + 5_500)).isEqualTo(86_405);
        // 2026-10-03T00:00:00Z is preprod slot 135,302,400 (the naive (now - start) / 1 s would give 136,944,000).
        assertThat(PREPROD.slotAt(1_790_985_600_000L)).isEqualTo(135_302_400L);
    }

    @Test
    void slotStartIsTheInverse() {
        for (long slot : new long[] {0, 1, 86_399, 86_400, 86_401, 135_302_400L}) {
            long start = PREPROD.slotStartMillis(slot);
            assertThat(PREPROD.slotAt(start)).isEqualTo(slot);
            assertThat(PREPROD.slotAt(start - 1)).isEqualTo(slot - 1);
        }
    }

    @Test
    void anUnknownFirstShelleySlotMakesEverySlotAByronSlot() {
        SlotClock unknown = new SlotClock(PREPROD_START, 20_000, 1_000, () -> Long.MAX_VALUE);

        assertThat(unknown.slotAt(PREPROD_START + 86_400L * 20_000 + 5_500)).isEqualTo(86_400);
        assertThat(unknown.slotAt(1_790_985_600_000L)).isEqualTo((1_790_985_600_000L - PREPROD_START) / 20_000);
        assertThat(unknown.slotAt(Long.MAX_VALUE)).isEqualTo((Long.MAX_VALUE - PREPROD_START) / 20_000);
    }

    @Test
    void withoutAByronPrefixItIsElapsedOverSlotLength() {
        SlotClock devnet = SlotClock.shelleyOnly(1_000, 200);

        assertThat(devnet.slotAt(999)).isEqualTo(-1);
        assertThat(devnet.slotAt(1_000)).isZero();
        assertThat(devnet.slotAt(1_000 + 200 * 7 + 199)).isEqualTo(7);
        assertThat(devnet.slotStartMillis(8)).isEqualTo(2_600);
    }
}
