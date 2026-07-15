package com.bloxbean.cardano.yano.appchain.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProofVerifierSecurityTest {

    @Test
    @Timeout(5)
    void deeplyNestedCborIsRejectedWithoutEnteringRecursiveDecoder() {
        byte[] nested = new byte[100_002];
        Arrays.fill(nested, 0, nested.length - 1, (byte) 0x81);
        nested[nested.length - 1] = (byte) 0x80;

        assertThatCode(() -> {
            assertThat(ProofVerifier.verifyInclusion(
                    new byte[32], new byte[]{1}, new byte[]{2}, nested)).isFalse();
            assertThat(ProofVerifier.verifyExclusion(
                    new byte[32], new byte[]{1}, nested)).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    @Timeout(5)
    void flatProofThatWouldOverflowRecursiveVerifierIsRejectedByPathBound() {
        byte[] proof = repeatedBranches(7_800);
        assertThat(proof).hasSizeLessThan(1024 * 1024 + 1);

        assertThatCode(() -> assertThat(ProofVerifier.verifyExclusion(
                new byte[32], new byte[]{1}, proof)).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void proofShapesAndCumulativePathLengthAreValidatedBeforeDecode() {
        assertThat(ProofVerifier.verifyExclusion(
                new byte[32], new byte[]{1}, branch(64, 128))).isFalse();
        assertThat(ProofVerifier.verifyExclusion(
                new byte[32], new byte[]{1}, branch(0, 127))).isFalse();
    }

    @Test
    void malformedPublicProofAndOversizedInputsFailClosed() {
        AppChainClient.Proof malformed = new AppChainClient.Proof(
                "not-hex", "chain", "also-not-hex", "zz", "01", null);
        assertThat(ProofVerifier.verify(malformed)).isFalse();
        assertThat(ProofVerifier.verify(null)).isFalse();
        assertThat(ProofVerifier.verifyInclusion(
                new byte[31], new byte[]{1}, new byte[]{2}, new byte[]{(byte) 0x80}))
                .isFalse();
        assertThat(ProofVerifier.verifyExclusion(
                new byte[32], new byte[257], new byte[]{(byte) 0x80}))
                .isFalse();
        assertThat(ProofVerifier.verifyExclusion(
                new byte[32], new byte[]{1}, new byte[1024 * 1024 + 1]))
                .isFalse();
    }

    private static byte[] repeatedBranches(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x99);
        out.write((count >>> 8) & 0xff);
        out.write(count & 0xff);
        for (int index = 0; index < count; index++) {
            writeBranch(out, 0, 128);
        }
        return out.toByteArray();
    }

    private static byte[] branch(int skip, int neighborBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        writeBranch(out, skip, neighborBytes);
        return out.toByteArray();
    }

    private static void writeBranch(ByteArrayOutputStream out, int skip, int neighborBytes) {
        out.write(0xd8);
        out.write(0x79);
        out.write(0x82);
        if (skip < 24) {
            out.write(skip);
        } else {
            out.write(0x18);
            out.write(skip);
        }
        out.write(0x58);
        out.write(neighborBytes);
        out.writeBytes(new byte[neighborBytes]);
    }
}
