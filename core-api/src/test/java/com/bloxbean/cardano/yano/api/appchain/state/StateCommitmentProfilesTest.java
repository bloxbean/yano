package com.bloxbean.cardano.yano.api.appchain.state;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Test
    void stateIdentitySettingsAreAllOrNothingCanonicalAndDefensive() {
        byte[] genesisId = new byte[32];
        genesisId[0] = 7;
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF, genesisId);
        genesisId[0] = 9;

        assertThat(identity.genesisId()[0]).isEqualTo((byte) 7);
        assertThat(StateCommitmentIdentity.fromSettings(identity.settings())).isEqualTo(identity);
        assertThat(StateCommitmentIdentity.fromSettings(Map.of()).legacy()).isTrue();
        assertThat(identity.digest()).hasSize(32);
        assertThat(identity.canonicalBytes()).isNotEmpty();

        assertThatThrownBy(() -> StateCommitmentIdentity.fromSettings(Map.of(
                StateCommitmentIdentity.PROFILE_SETTING,
                StateCommitmentProfiles.MPF_BLAKE2B256_V1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured together");
        assertThatThrownBy(() -> StateCommitmentIdentity.fromSettings(Map.of(
                StateCommitmentIdentity.PROFILE_SETTING, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured together");
        Map<String, String> whitespace = new LinkedHashMap<>(identity.settings());
        whitespace.put(StateCommitmentIdentity.PROFILE_SETTING,
                " " + StateCommitmentProfiles.MPF_BLAKE2B256_V1);
        assertThatThrownBy(() -> StateCommitmentIdentity.fromSettings(whitespace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");

        Map<String, String> uppercase = new LinkedHashMap<>(identity.settings());
        uppercase.put(StateCommitmentIdentity.GENESIS_ID_SETTING, "AA" + "00".repeat(31));
        assertThatThrownBy(() -> StateCommitmentIdentity.fromSettings(uppercase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical lowercase hex");

        Map<String, String> wrongFingerprint = new LinkedHashMap<>(identity.settings());
        wrongFingerprint.put(StateCommitmentIdentity.FINGERPRINT_SETTING, "00".repeat(32));
        assertThatThrownBy(() -> StateCommitmentIdentity.fromSettings(wrongFingerprint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }
}
