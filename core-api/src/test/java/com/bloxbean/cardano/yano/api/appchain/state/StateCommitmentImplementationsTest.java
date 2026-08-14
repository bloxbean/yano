package com.bloxbean.cardano.yano.api.appchain.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StateCommitmentImplementationsTest {
    @Test
    void compatibilityMetadataIsSeparateFromConsensusFingerprint() {
        byte[] before = StateCommitmentProfiles.MPF.formatFingerprint();
        StateCommitmentImplementation metadata = StateCommitmentImplementations.require(
                StateCommitmentProfiles.MPF_BLAKE2B256_V1);

        assertThat(metadata.compatibility()).contains("Cardano Client Lib");
        assertThat(metadata.profileId()).doesNotContain("ccl").doesNotContain("zeroj");
        assertThat(StateCommitmentProfiles.MPF.commitmentFormatId()).doesNotContain("ccl");
        assertThat(StateCommitmentProfiles.MPF.proofEncodingId()).doesNotContain("ccl");
        assertThat(StateCommitmentProfiles.MPF.formatFingerprint()).isEqualTo(before);
    }

    @Test
    void jmtIsExplicitlyOffChainOnly() {
        assertThat(StateCommitmentImplementations.require(
                StateCommitmentProfiles.JMT_BLAKE2B256_V1).verificationTarget())
                .isEqualTo(StateCommitmentImplementation.VerificationTarget.OFF_CHAIN_ONLY);
    }
}
