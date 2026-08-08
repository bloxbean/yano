package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MpfOnChainVerifierTest extends ContractTest {
    @BeforeAll
    static void setUpCrypto() {
        initCrypto();
    }

    @Test
    void commonSingleLeafVectorMatchesReleasedMpfRoot() {
        byte[] key = new byte[]{1, 2, 3};
        byte[] value = new byte[]{4, 5, 6};
        byte[] path = nibbles(Blake2bUtil.blake2bHash256(key));
        byte[] suffix = encodeLeafSuffix(path);
        byte[] root = Blake2bUtil.blake2bHash256(concat(
                suffix, Blake2bUtil.blake2bHash256(value)));
        var proof = new MpfOnChainVerifier.Proof(
                key, value, suffix, JulcList.empty());

        assertThat(MpfOnChainVerifier.verifyInclusion(proof, root)).isTrue();
        assertThat(MpfOnChainVerifier.verifyInclusion(proof, filled(9))).isFalse();
    }

    @Test
    void referenceValidatorCompilesToTheActualOnChainTarget() {
        assertThat(compileValidator(MpfOnChainVerifier.class).program()).isNotNull();
    }

    private static byte[] nibbles(byte[] path) {
        byte[] result = new byte[path.length * 2];
        for (int i = 0; i < path.length; i++) {
            result[i * 2] = (byte) ((path[i] >>> 4) & 15);
            result[i * 2 + 1] = (byte) (path[i] & 15);
        }
        return result;
    }

    private static byte[] encodeLeafSuffix(byte[] suffix) {
        byte[] encoded = new byte[1 + suffix.length / 2];
        encoded[0] = (byte) 0xff;
        for (int i = 0; i < suffix.length; i += 2) {
            encoded[1 + i / 2] = (byte) ((suffix[i] << 4) | suffix[i + 1]);
        }
        return encoded;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
