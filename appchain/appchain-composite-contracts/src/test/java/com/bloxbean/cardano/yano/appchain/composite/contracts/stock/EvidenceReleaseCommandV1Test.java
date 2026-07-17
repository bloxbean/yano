package com.bloxbean.cardano.yano.appchain.composite.contracts.stock;

import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceReleaseCommandV1Test {
    @Test
    void canonicalWireHasFrozenDigestAndRejectsTrailingBytes() throws Exception {
        SubmitEvidenceCommandV1 evidence = evidence();
        EvidenceReleaseCommandV1 command = new EvidenceReleaseCommandV1(
                "release-42", "product-42".getBytes(StandardCharsets.US_ASCII),
                "approval-42", "product-42", filled(0x44),
                "object:sample-42/inspection-certificate.bin", evidence.encode());

        byte[] encoded = command.encode();
        assertThat(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(encoded))).isEqualTo(
                "ab71a7a4cb3ac78488c88310b94f840c808f7152568d7eeb66995763d42a6aba");
        assertThat(EvidenceReleaseCommandV1.decode(encoded)).isEqualTo(command);
        assertThatThrownBy(() -> EvidenceReleaseCommandV1.decode(
                Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-canonical");
    }

    private static SubmitEvidenceCommandV1 evidence() {
        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                "archive", "staging/certificate.bin", "evidence/certificate.bin",
                DigestAlgorithm.SHA_256, filled(0x11), 32,
                "application/octet-stream", null);
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1("local", CanonicalCid.fromText(
                "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi"),
                true, "demo-single");
        return new SubmitEvidenceCommandV1("sample-42", 1,
                object.encode(), filled(0x22), ipfs.encode(), filled(0x33),
                "primary", "evidence-ready", filled(0x55));
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
