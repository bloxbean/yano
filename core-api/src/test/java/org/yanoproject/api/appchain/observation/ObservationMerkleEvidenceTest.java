package org.yanoproject.api.appchain.observation;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationMerkleEvidenceTest {
    @Test
    void signedRootProvesOnlyTheRequestedLeafAndRound() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicEncoding = signer.getPublic().getEncoded();
        byte[] publicKey = Arrays.copyOfRange(publicEncoding, publicEncoding.length - 32, publicEncoding.length);
        ObservationRound round = round(id(3));
        byte[] value = new byte[]{1, 2, 3};
        byte[] sibling = id(8);
        byte[] root = ObservationMerkleEvidence.branchHash(sibling,
                ObservationMerkleEvidence.leafHash(round.parametersDigest(), id(4), value));
        // Independently calculated with Python hashlib.blake2b(digest_size=32).
        assertThat(ObservationMerkleEvidence.leafHash(round.parametersDigest(), id(4), value))
                .isEqualTo(HexFormat.of().parseHex("8f64e61c399eb6a94149f28dea276f9dd996bc4acc843d0a65f8703cfa63d14f"));
        assertThat(root)
                .isEqualTo(HexFormat.of().parseHex("7b372f1a3f79f345f2dc8fb82fc46cd3492c20e90ff891e744de17a4034758b0"));
        ObservationAttestation unsigned = root(round, publicKey, root, new byte[64]);
        Signature signing = Signature.getInstance("Ed25519");
        signing.initSign(signer.getPrivate());
        signing.update(unsigned.signingDigest());
        ObservationMerkleEvidence proof = new ObservationMerkleEvidence(1,
                root(round, publicKey, root, signing.sign()), value, 1, List.of(sibling));
        ObservationReport report = report(round, proof, value, 10);
        ObservationSignatureVerifier verifier = (key, digest, signature) -> {
            try {
                Signature verify = Signature.getInstance("Ed25519");
                verify.initVerify(signer.getPublic());
                verify.update(digest);
                return Arrays.equals(key, publicKey) && verify.verify(signature);
            } catch (Exception invalid) {
                return false;
            }
        };
        assertThat(proof.verify(round, report, List.of(publicKey), verifier)).isTrue();
        assertThat(proof.verify(round(id(9)), report, List.of(publicKey), verifier)).isFalse();
        assertThat(proof.verify(round, report, List.of(id(9)), verifier)).isFalse();
        assertThat(proof.verify(round, report(round, proof, new byte[]{1}, 10), List.of(publicKey), verifier))
                .isFalse();
        assertThat(proof.verify(round, report(round, proof, value, 11), List.of(publicKey), verifier)).isFalse();
        var wrongPath = new ObservationMerkleEvidence(1, proof.rootAttestation(), value, 0, List.of(sibling));
        assertThat(wrongPath.verify(round, report, List.of(publicKey), verifier)).isFalse();
        var wrongSibling = new ObservationMerkleEvidence(1, proof.rootAttestation(), value, 1, List.of(id(7)));
        assertThat(wrongSibling.verify(round, report, List.of(publicKey), verifier)).isFalse();
        var wrongSignature = new ObservationMerkleEvidence(1, unsigned, value, 1, List.of(sibling));
        assertThat(wrongSignature.verify(round, report, List.of(publicKey), verifier)).isFalse();
    }

    @Test
    void framingDepthIndexAndDefensiveCopiesAreBounded() {
        ObservationRound round = round(id(3));
        byte[] sibling = id(8);
        ObservationMerkleEvidence proof = new ObservationMerkleEvidence(1,
                root(round, id(5), id(6), new byte[64]), new byte[]{1}, 0, List.of(sibling));
        byte[] encoded = proof.encode();
        sibling[0]++;
        proof.siblings().getFirst()[0]++;
        proof.value()[0]++;
        assertThat(proof.encode()).isEqualTo(encoded);
        assertThat(ObservationMerkleEvidence.decode(encoded).encode()).isEqualTo(encoded);
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> ObservationMerkleEvidence.decode(truncated))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ObservationMerkleEvidence.decode(Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationMerkleEvidence(1, proof.rootAttestation(), new byte[0], 2,
                List.of(id(8)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationMerkleEvidence(1, proof.rootAttestation(), new byte[0], 0,
                Collections.nCopies(21, id(8)))).isInstanceOf(IllegalArgumentException.class);
        var deepest = new ObservationMerkleEvidence(1, proof.rootAttestation(), new byte[64 * 1024],
                (1 << 20) - 1, Collections.nCopies(20, id(8)));
        assertThat(deepest.encode().length).isLessThanOrEqualTo(ObservationMerkleEvidence.MAX_ENCODED_BYTES);
        assertThat(ObservationMerkleEvidence.decode(deepest.encode()).computedRoot(round.parametersDigest()))
                .isEqualTo(deepest.computedRoot(round.parametersDigest()));
        var single = new ObservationMerkleEvidence(1, proof.rootAttestation(), new byte[]{1}, 0, List.of());
        assertThat(single.computedRoot(round.parametersDigest()))
                .isEqualTo(ObservationMerkleEvidence.leafHash(round.parametersDigest(), id(4), new byte[]{1}));
    }

    private static ObservationAttestation root(ObservationRound round, byte[] signer, byte[] hash, byte[] signature) {
        return new ObservationAttestation(1, round.definitionDigest(), round.subscriptionId(), round.roundNumber(),
                signer, id(4), hash, new byte[]{1}, 0, 10, signature);
    }

    private static ObservationRound round(byte[] parametersDigest) {
        return new ObservationRound(1, id(1), 0, ObservationAnchorType.APP_HEIGHT, 10, 10, 12, 3, 20, 0,
                id(2), parametersDigest, 0, id(6), 5, 4, 1, ObservationReporterMode.ACTIVE_MEMBERS,
                id(7), 5, 1, 4, id(8), id(9));
    }

    private static ObservationReport report(ObservationRound round, ObservationMerkleEvidence proof,
                                              byte[] value, long freshness) {
        return new ObservationReport(1, id(20), "chain", id(21), id(22), round.definitionDigest(),
                round.subscriptionId(), round.roundNumber(), round.membershipDigest(), round.reporterSetDigest(),
                id(23), id(4), value, proof.encode(), new byte[]{1}, 0, freshness, new byte[64]);
    }

    private static byte[] id(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
