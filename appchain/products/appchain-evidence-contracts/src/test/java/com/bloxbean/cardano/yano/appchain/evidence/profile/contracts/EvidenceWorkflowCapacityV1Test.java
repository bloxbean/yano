package com.bloxbean.cardano.yano.appchain.evidence.profile.contracts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceWorkflowCapacityV1Test {
    @Test
    void derivesFrozenGatedAndDirectReservations() {
        EvidenceWorkflowCapacityV1 capacity = new EvidenceWorkflowCapacityV1(8);

        assertThat(capacity.releaseWorkflowEffects()).isEqualTo(16);
        assertThat(capacity.notificationWorkflowEffects()).isEqualTo(8);
        assertThat(capacity.gatedEvidenceComponentEffects()).isEqualTo(8);
        assertThat(capacity.directEvidenceComponentEffects()).isEqualTo(16);
        assertThat(capacity.totalReservedEffects()).isEqualTo(32);
    }

    @Test
    void rejectsValuesOutsideFrozenBounds() {
        assertThatThrownBy(() -> new EvidenceWorkflowCapacityV1(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1");
        assertThatThrownBy(() -> new EvidenceWorkflowCapacityV1(
                EvidenceWorkflowCapacityV1.MAX_CAPACITY + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1");
    }

    @Test
    void validatesBothConsensusCaps() {
        EvidenceWorkflowCapacityV1 capacity = new EvidenceWorkflowCapacityV1(8);

        capacity.validateAgainst(64, 32);
        assertThatThrownBy(() -> capacity.validateAgainst(7, 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block.max-messages");
        assertThatThrownBy(() -> capacity.validateAgainst(64, 31))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effects.max-per-block >= 32");
    }
}
