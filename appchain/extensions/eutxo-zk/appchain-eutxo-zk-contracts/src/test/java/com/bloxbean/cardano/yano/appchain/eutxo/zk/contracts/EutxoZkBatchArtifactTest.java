package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZkBatchArtifactTest {

    @Test
    void batchProfileAndArtifactsAreCanonicalAndSecurityBound() {
        EutxoZkBatchProfile profile =
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        EutxoZkBatchVerificationKey key =
                new EutxoZkBatchVerificationKey(
                        profile.id(),
                        profile.digest(),
                        profile.authorizationProfile(),
                        profile.circuitId(),
                        bytes(48, 1),
                        bytes(96, 2),
                        bytes(96, 3),
                        bytes(96, 4),
                        List.of(
                                bytes(48, 5),
                                bytes(48, 6),
                                bytes(48, 7),
                                bytes(48, 8),
                                bytes(48, 9),
                                bytes(48, 10),
                                bytes(48, 11),
                                bytes(48, 12),
                                bytes(48, 13)));
        EutxoZkBatchProof proof = new EutxoZkBatchProof(
                profile.id(),
                profile.digest(),
                profile.authorizationProfile(),
                key.digestHex(),
                List.of(
                        BigInteger.ONE,
                        BigInteger.TWO,
                        BigInteger.valueOf(3),
                        BigInteger.valueOf(4),
                        BigInteger.ONE,
                        BigInteger.valueOf(6),
                        BigInteger.valueOf(7),
                        BigInteger.valueOf(8)),
                List.of("aa".repeat(32)),
                bytes(48, 14),
                bytes(96, 15),
                bytes(48, 16),
                17);

        assertThat(EutxoZkBatchVerificationKey.decode(
                key.canonicalBytes())).isEqualTo(key);
        assertThat(EutxoZkBatchProof.decode(
                proof.canonicalBytes())).isEqualTo(proof);
        assertThat(profile.digest()).matches("[0-9a-f]{64}");

        assertThatThrownBy(() -> new EutxoZkBatchVerificationKey(
                profile.id(),
                profile.digest(),
                "zeroj-jubjub-hardened-v1",
                profile.circuitId(),
                bytes(48, 1),
                bytes(96, 2),
                bytes(96, 3),
                bytes(96, 4),
                key.ic().subList(0, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nine IC");
    }

    private static byte[] bytes(int count, int value) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
