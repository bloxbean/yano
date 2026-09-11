package com.bloxbean.cardano.yano.runtime.blockproducer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackfillPolicyTest {
    @Test
    void automaticSpacingReservesForecastHeadroom() {
        assertThat(BackfillPolicy.resolveInterval(0, 100, 1, false)).isEqualTo(299);
        assertThat(BackfillPolicy.resolveInterval(0, 100, 1, true)).isEqualTo(150);
        assertThat(BackfillPolicy.resolveInterval(0, 100, 0.2, false)).isEqualTo(1499);
        assertThat(BackfillPolicy.resolveInterval(1, 100, 1, true)).isEqualTo(1);
        assertThat(BackfillPolicy.resolveInterval(42, 100, 1, false)).isEqualTo(42);
    }

    @Test
    void rejectsIntervalsAtForecastLimitAndInvalidGenesis() {
        assertThatThrownBy(() -> BackfillPolicy.resolveInterval(300, 100, 1, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forecast window");
        assertThatThrownBy(() -> BackfillPolicy.resolveInterval(0, 0, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackfillPolicy.resolveInterval(0, 100, Double.NaN, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
