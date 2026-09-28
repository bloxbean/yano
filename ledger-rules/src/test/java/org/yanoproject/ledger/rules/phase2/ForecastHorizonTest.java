package org.yanoproject.ledger.rules.phase2;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.util.EpochSlotCalc;

import static org.assertj.core.api.Assertions.assertThat;

/** The HFC forecast horizon: the first epoch boundary at or after next(tip) + 3k/f (exclusive). */
class ForecastHorizonTest {

    @Test
    void roundsUpToTheNextEpochBoundary() {
        ForecastHorizon horizon = ForecastHorizon.of(() -> 300, new EpochSlotCalc(1000, 1000, 0));

        assertThat(horizon.exclusiveUpperSlot(101)).isEqualTo(1000);   // 401 → end of epoch 0
        assertThat(horizon.exclusiveUpperSlot(700)).isEqualTo(1000);   // 1000 is a boundary: stays
        assertThat(horizon.exclusiveUpperSlot(701)).isEqualTo(2000);   // 1001 → end of epoch 1
    }

    @Test
    void followsTheByronPrefix() {
        // 4 Byron epochs of 21600 slots, then 432000-slot epochs (preprod geometry), k=2160, f=1/20.
        ForecastHorizon horizon = ForecastHorizon.of(() -> 129_600, new EpochSlotCalc(432_000, 21_600, 86_400));

        assertThat(horizon.exclusiveUpperSlot(86_400)).isEqualTo(86_400 + 432_000);
    }
}
