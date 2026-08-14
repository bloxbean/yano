package com.bloxbean.cardano.yano.api.appchain.state;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateCommitmentApplicationProfileTest {
    @Test
    void committedApplicationProfileDerivesAStableFreshGeneration() {
        byte[] genesis = new byte[32];
        byte[] profileA = new byte[32];
        byte[] profileB = new byte[32];
        Arrays.fill(genesis, (byte) 1);
        Arrays.fill(profileA, (byte) 2);
        Arrays.fill(profileB, (byte) 3);
        StateCommitmentIdentity base = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF, genesis);

        StateCommitmentIdentity first = base.withApplicationProfile(profileA);

        assertThat(first).isEqualTo(base.withApplicationProfile(profileA));
        assertThat(first.genesisId()).isNotEqualTo(base.genesisId());
        assertThat(first.genesisId())
                .isNotEqualTo(base.withApplicationProfile(profileB).genesisId());
        assertThat(first.profile()).isEqualTo(base.profile());
        assertThatThrownBy(() -> base.withApplicationProfile(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
