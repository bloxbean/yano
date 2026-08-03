package com.bloxbean.cardano.yano.api.appchain.state;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateCommitmentProfilesTest {
    @Test
    void catalogHasThreeDistinctCanonicalProfiles() {
        assertThat(StateCommitmentProfiles.all()).hasSize(3);
        assertThat(new HashSet<>(StateCommitmentProfiles.all().stream()
                .map(StateCommitmentProfile::id).toList())).hasSize(3);
        assertThat(new HashSet<>(StateCommitmentProfiles.all().stream()
                .map(profile -> java.util.HexFormat.of().formatHex(profile.formatFingerprint()))
                .toList())).hasSize(3);
        assertThat(StateCommitmentProfiles.CLASSIC_JMT.dependencyDescriptor())
                .isEqualTo("classic-radix16-blake2b256-v1");
    }

    @Test
    void aliasesAndMalformedDescriptorsFailClosed() {
        assertThatThrownBy(() -> StateCommitmentProfiles.require("jmt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateCommitmentProfile(1, "JMT",
                StateCommitmentProfile.BackendFamily.JMT, "descriptor", "proof", 32,
                true, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
