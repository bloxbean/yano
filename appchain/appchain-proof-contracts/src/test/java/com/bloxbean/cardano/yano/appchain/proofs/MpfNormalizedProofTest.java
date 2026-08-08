package com.bloxbean.cardano.yano.appchain.proofs;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MpfNormalizedProofTest {
    @Test
    void verifiesExactRootAndRejectsKeyValueRootSubstitution() {
        byte[] key = new byte[]{1, 2, 3};
        byte[] value = new byte[]{4, 5, 6};
        byte[] path = MpfNormalizedProof.nibbles(Blake2bUtil.blake2bHash256(key));
        byte[] suffix = MpfNormalizedProof.encodeLeafSuffix(path);
        byte[] root = MpfNormalizedProof.commitLeaf(
                suffix, Blake2bUtil.blake2bHash256(value));
        MpfNormalizedProof proof = new MpfNormalizedProof(
                root, key, value, suffix, List.of(), 9);

        assertThat(proof.verify()).isTrue();
        assertThat(new MpfNormalizedProof(filled(7), key, value, suffix, List.of(), 9)
                .verify()).isFalse();
        assertThat(new MpfNormalizedProof(root, new byte[]{9}, value, suffix, List.of(), 9)
                .verify()).isFalse();
        assertThat(new MpfNormalizedProof(root, key, new byte[]{9}, suffix, List.of(), 9)
                .verify()).isFalse();
    }

    @Test
    void enforcesPublishedHostileInputBounds() {
        assertThatThrownBy(() -> new MpfNormalizedProof(
                filled(1), new byte[257], new byte[]{1}, new byte[]{(byte) 0xff},
                List.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MpfNormalizedProof(
                filled(1), new byte[]{1}, new byte[8 * 1024 + 1],
                new byte[]{(byte) 0xff}, List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
