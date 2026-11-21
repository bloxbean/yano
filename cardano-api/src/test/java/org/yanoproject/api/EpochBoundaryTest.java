package org.yanoproject.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochBoundaryTest {

    @Test
    void copiesTheBlockHashOnTheWayInAndOut() {
        byte[] hash = new byte[32];
        var boundary = new EpochBoundary(4, 5, 432_000, hash, 1_000);
        hash[0] = 1;
        assertThat(boundary.blockHash()[0]).isZero();
        boundary.blockHash()[0] = 1;
        assertThat(boundary).isEqualTo(new EpochBoundary(4, 5, 432_000, new byte[32], 1_000))
                .hasSameHashCodeAs(new EpochBoundary(4, 5, 432_000, new byte[32], 1_000));
    }

    @Test
    void rejectsInvalidTransitions() {
        assertThatThrownBy(() -> new EpochBoundary(5, 5, 0, new byte[32], 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochBoundary(-1, 0, 0, new byte[32], 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochBoundary(4, 5, -1, new byte[32], 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochBoundary(4, 5, 0, new byte[32], -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochBoundary(4, 5, 0, new byte[31], 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochBoundary(4, 5, 0, null, 0))
                .isInstanceOf(NullPointerException.class);
    }
}
