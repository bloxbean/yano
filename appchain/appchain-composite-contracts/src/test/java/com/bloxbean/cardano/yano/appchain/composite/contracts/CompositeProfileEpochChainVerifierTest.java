package com.bloxbean.cardano.yano.appchain.composite.contracts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeProfileEpochChainVerifierTest {
    @Test
    void pinnedMarkerAndGovernedStructureBindExactContiguousBytes() {
        byte[] first = new byte[]{1, 2};
        byte[] second = new byte[]{3, 4};
        CompositeProfileEpochV1 zero = new CompositeProfileEpochV1(1, 0, 1,
                new byte[32], first, new byte[32]);
        byte[] proposal = new byte[32];
        proposal[0] = 1;
        CompositeProfileEpochV1 one = new CompositeProfileEpochV1(1, 1, 20,
                zero.profileDigest(), second, proposal);

        assertThat(CompositeProfileEpochChainVerifier.verifyPinnedMarker(
                second, List.of(one.profileDigest())).profileDigest())
                .containsExactly(one.profileDigest());
        assertThat(CompositeProfileEpochChainVerifier.verifyStructure(
                zero.profileDigest(), List.of(zero.encode(), one.encode()), second, 10)
                .epochNumber()).isEqualTo(1);
        assertThatThrownBy(() -> CompositeProfileEpochChainVerifier.verifyStructure(
                zero.profileDigest(), List.of(one.encode()), second, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompositeProfileEpochChainVerifier.verifyStructure(
                zero.profileDigest(), List.of(zero.encode(), one.encode()),
                new byte[]{9}, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CompositeCommitmentV1.profileEpochKey(1)).isNotEmpty();
    }
}
