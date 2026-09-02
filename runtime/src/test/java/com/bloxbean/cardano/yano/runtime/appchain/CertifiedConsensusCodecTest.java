package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CertifiedConsensusCodecTest {

    @Test
    void voteAndCertificateAreCanonicalAndPhaseSeparated() {
        byte[] context = filled(1, 32);
        byte[] block = filled(2, 32);
        byte[] value = filled(4, 32);
        byte[] signature = filled(3, 64);
        CertifiedConsensusCodec.Vote vote = new CertifiedConsensusCodec.Vote(
                CertifiedConsensusCodec.Phase.PREPARE, 9, 4, context, block, value, signature);

        CertifiedConsensusCodec.Vote decodedVote = CertifiedConsensusCodec.decodeVote(
                CertifiedConsensusCodec.encodeVote(vote));
        assertThat(HexUtil.encodeHexString(CertifiedConsensusCodec.encodeVote(vote)))
                .isEqualTo("88020009045820" + "01".repeat(32)
                        + "5820" + "02".repeat(32)
                        + "5820" + "04".repeat(32)
                        + "5840" + "03".repeat(64));
        assertThat(decodedVote.phase()).isEqualTo(vote.phase());
        assertThat(decodedVote.height()).isEqualTo(vote.height());
        assertThat(decodedVote.view()).isEqualTo(vote.view());
        assertThat(decodedVote.contextDigest()).isEqualTo(vote.contextDigest());
        assertThat(decodedVote.blockHash()).isEqualTo(vote.blockHash());
        assertThat(decodedVote.valueHash()).isEqualTo(vote.valueHash());
        assertThat(decodedVote.signature()).isEqualTo(vote.signature());
        assertThat(CertifiedConsensusCodec.signingDigest(
                CertifiedConsensusCodec.Phase.PREPARE, 9, 4, context, block, value))
                .isNotEqualTo(CertifiedConsensusCodec.signingDigest(
                        CertifiedConsensusCodec.Phase.COMMIT, 9, 4, context, block, value));
        assertThat(CertifiedConsensusCodec.signingDigest(
                CertifiedConsensusCodec.Phase.PREPARE, 9, 4, context, block, value))
                .isNotEqualTo(CertifiedConsensusCodec.signingDigest(
                        CertifiedConsensusCodec.Phase.PREPARE, 9, 4, context, block,
                        filled(5, 32)));
        AppMessageSigner signer = new AppMessageSigner(
                HexUtil.encodeHexString(filled(9, 32)));
        byte[] certified = signer.sign(CertifiedConsensusCodec.signingDigest(
                CertifiedConsensusCodec.Phase.PREPARE, 9, 4, context, block, value));
        assertThat(AppMessageSigner.verify(certified,
                CertifiedConsensusCodec.signingDigest(
                        CertifiedConsensusCodec.Phase.PREPARE, 9, 4, context, block,
                        filled(5, 32)), signer.publicKey())).isFalse();

        CertifiedConsensusCodec.QuorumCertificate qc =
                new CertifiedConsensusCodec.QuorumCertificate(
                        CertifiedConsensusCodec.Phase.PREPARE, 9, 4, context, block,
                        filled(6, 32),
                        List.of(signature(8), signature(7)));
        CertifiedConsensusCodec.QuorumCertificate decoded =
                CertifiedConsensusCodec.decodeQc(CertifiedConsensusCodec.encodeQc(qc));
        assertThat(CertifiedConsensusCodec.encodeQc(decoded))
                .isEqualTo(CertifiedConsensusCodec.encodeQc(qc));
        assertThat(decoded.signatures().getFirst().signer()[0]).isEqualTo((byte) 7);
    }

    @Test
    void rejectsDuplicateSignersAndWrongWidths() {
        FinalityCert.Signature duplicate = signature(7);
        assertThatThrownBy(() -> new CertifiedConsensusCodec.QuorumCertificate(
                CertifiedConsensusCodec.Phase.COMMIT, 1, 0, filled(1, 32), filled(2, 32),
                filled(4, 32),
                List.of(duplicate, duplicate))).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new CertifiedConsensusCodec.Vote(
                CertifiedConsensusCodec.Phase.PREPARE, 1, 0,
                new byte[31], filled(2, 32), filled(4, 32), filled(3, 64)))
                .hasMessageContaining("context digest");
    }

    @Test
    void timeoutAndNewViewAreCanonicalBoundedAndSignerOrdered() {
        byte[] context = filled(1, 32);
        CertifiedConsensusCodec.Timeout first = new CertifiedConsensusCodec.Timeout(
                9, 2, context, new byte[0], new byte[0], filled(4, 64));
        CertifiedConsensusCodec.Timeout second = new CertifiedConsensusCodec.Timeout(
                9, 2, context, new byte[0], new byte[0], filled(5, 64));
        assertThat(CertifiedConsensusCodec.encodeTimeout(
                CertifiedConsensusCodec.decodeTimeout(
                        CertifiedConsensusCodec.encodeTimeout(first))))
                .isEqualTo(CertifiedConsensusCodec.encodeTimeout(first));
        assertThat(CertifiedConsensusCodec.timeoutSigningDigest(
                9, 2, context, new byte[0], new byte[0]))
                .isNotEqualTo(CertifiedConsensusCodec.signingDigest(
                        CertifiedConsensusCodec.Phase.COMMIT, 9, 2,
                        context, filled(2, 32), filled(3, 32)));

        CertifiedConsensusCodec.NewViewCertificate certificate =
                new CertifiedConsensusCodec.NewViewCertificate(9, 2, context, List.of(
                        new CertifiedConsensusCodec.SignedTimeout(filled(8, 32), first),
                        new CertifiedConsensusCodec.SignedTimeout(filled(7, 32), second)));
        CertifiedConsensusCodec.NewViewCertificate decoded =
                CertifiedConsensusCodec.decodeNewView(
                        CertifiedConsensusCodec.encodeNewView(certificate));
        assertThat(decoded.timeouts().getFirst().signer()[0]).isEqualTo((byte) 7);
        assertThat(CertifiedConsensusCodec.encodeNewView(decoded))
                .isEqualTo(CertifiedConsensusCodec.encodeNewView(certificate));

        CertifiedConsensusCodec.QuorumCertificate finality =
                new CertifiedConsensusCodec.QuorumCertificate(
                        CertifiedConsensusCodec.Phase.COMMIT, 9, 1, context,
                        filled(2, 32), filled(3, 32), List.of(signature(7)));
        CertifiedConsensusCodec.Timeout withFinality = new CertifiedConsensusCodec.Timeout(
                9, 2, context, new byte[0], CertifiedConsensusCodec.encodeQc(finality),
                filled(6, 64));
        assertThat(CertifiedConsensusCodec.decodeTimeout(
                CertifiedConsensusCodec.encodeTimeout(withFinality)).finalityCertificate())
                .isEqualTo(CertifiedConsensusCodec.encodeQc(finality));
    }

    private static FinalityCert.Signature signature(int value) {
        return new FinalityCert.Signature(filled(value, 32), filled(value + 1, 64));
    }

    private static byte[] filled(int value, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
