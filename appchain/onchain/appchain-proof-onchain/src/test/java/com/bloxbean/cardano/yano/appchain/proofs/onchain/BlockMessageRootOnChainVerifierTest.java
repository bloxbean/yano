package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessageRootIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BlockMessageRootOnChainVerifierTest extends ContractTest {
    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void verifiesNestedSingleMessageVectorAndRejectsEveryBindingMutation() {
        byte[] messageId = filled(7);
        byte[] key = FinalizedBlockMessageRootIndex.blockKey(9);
        byte[] value = new FinalizedBlockMessageRootIndex.BlockRecord(
                9, messageId, 1).canonicalBytes();
        byte[] path = nibbles(Blake2bUtil.blake2bHash256(key));
        byte[] suffix = encodeLeafSuffix(path);
        byte[] root = Blake2bUtil.blake2bHash256(concat(
                suffix, Blake2bUtil.blake2bHash256(value)));
        var proof = new MpfOnChainVerifier.Proof(key, value, suffix, JulcList.empty());
        var claim = new BlockMessageRootOnChainVerifier.Claim(proof, key,
                BigInteger.valueOf(9), messageId, BigInteger.ZERO, BigInteger.ONE,
                JulcList.empty());

        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(claim, root)).isTrue();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(claim, filled(8))).isFalse();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(new
                BlockMessageRootOnChainVerifier.Claim(proof, filled(8), BigInteger.valueOf(9),
                messageId, BigInteger.ZERO, BigInteger.ONE, JulcList.empty()), root)).isFalse();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(new
                BlockMessageRootOnChainVerifier.Claim(proof, key, BigInteger.valueOf(10),
                messageId, BigInteger.ZERO, BigInteger.ONE, JulcList.empty()), root)).isFalse();
        assertThat(BlockMessageRootOnChainVerifier.verifyAtRoot(new
                BlockMessageRootOnChainVerifier.Claim(proof, key, BigInteger.valueOf(9),
                filled(6), BigInteger.ZERO, BigInteger.ONE, JulcList.empty()), root)).isFalse();
    }

    @Test
    void referenceValidatorCompilesToTheOnChainTarget() {
        assertThat(compileValidator(BlockMessageRootAnchorValidator.class).program()).isNotNull();
    }

    private static byte[] nibbles(byte[] path) {
        byte[] result = new byte[path.length * 2];
        for (int index = 0; index < path.length; index++) {
            result[index * 2] = (byte) ((path[index] >>> 4) & 15);
            result[index * 2 + 1] = (byte) (path[index] & 15);
        }
        return result;
    }
    private static byte[] encodeLeafSuffix(byte[] suffix) {
        byte[] encoded = new byte[1 + suffix.length / 2];
        encoded[0] = (byte) 0xff;
        for (int index = 0; index < suffix.length; index += 2) {
            encoded[1 + index / 2] = (byte) ((suffix[index] << 4) | suffix[index + 1]);
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
