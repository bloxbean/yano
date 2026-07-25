package com.bloxbean.cardano.yano.api.appchain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppAnchorCommitmentTest {

    @Test
    void copiesConsensusIdentityArraysOnConstructionAndAccess() {
        byte[] root = filled(1);
        byte[] blockHash = filled(2);
        AppAnchorCommitment commitment = new AppAnchorCommitment(
                "orders", "metadata", 7, root, blockHash, "ab", 123);

        root[0] = 9;
        blockHash[0] = 9;
        assertThat(commitment.stateRoot()).containsOnly((byte) 1);
        assertThat(commitment.blockHash()).containsOnly((byte) 2);

        commitment.stateRoot()[0] = 8;
        commitment.blockHash()[0] = 8;
        assertThat(commitment.stateRoot()).containsOnly((byte) 1);
        assertThat(commitment.blockHash()).containsOnly((byte) 2);
    }

    @Test
    void rejectsIncompleteOrInvalidCommitments() {
        assertThatThrownBy(() -> new AppAnchorCommitment(
                " ", "metadata", 1, filled(1), filled(2), "ab", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainId");
        assertThatThrownBy(() -> new AppAnchorCommitment(
                "orders", "metadata", -1, filled(1), filled(2), "ab", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new AppAnchorCommitment(
                "orders", "metadata", 1, new byte[31], filled(2), "ab", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateRoot");
        assertThatThrownBy(() -> new AppAnchorCommitment(
                "orders", "metadata", 1, filled(1), new byte[31], "ab", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockHash");
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
