package com.bloxbean.cardano.yano.appchain.integration.ipfs;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CanonicalCidTest {
    private static final String CID_V0 = "QmYwAPJzv5CZsnAzt8auVZRnGiR7JagPyV7LSKmhQfBfZP";
    private static final String CID_V0_HEX =
            "12209d6c2be50f70695347c6da90ab413d0fdb93dd37d3ba8240312a94b8407ca1fe";
    private static final String CID_V0_NORMALIZED_HEX =
            "017012209d6c2be50f70695347c6da90ab413d0fdb93dd37d3ba8240312a94b8407ca1fe";
    private static final String CID_V0_NORMALIZED_BASE32 =
            "bafybeie5nqv6kd3qnfjuprw2scvucpip3oj52n6txkbeamjkss4ea7fb7y";
    private static final String CID_V0_NORMALIZED_BASE58 =
            "zdj7Wg2Qkk4mYgAkVUvcsYXMwGLtvcZ286XqoWs78ANE6kZjK";

    private static final String CID_V1_DAG_PB =
            "bafybeie5nqv6kd3qnfjuprw2scvucpip6mb5fz6w6ekdpkzot4l3hk5x5i";
    private static final String CID_V1_DAG_PB_BASE58 =
            "zdj7Wg2Qkk4mYgAkVUvcsYXMwGLwpUT3g9yHo7qGeBcr7oXk5";
    private static final String CID_V1_DAG_PB_HEX =
            "017012209d6c2be50f70695347c6da90ab413d0ff303d2e7d6f11437ab2e9f17b3abb7ea";

    private static final String CID_V1_RAW =
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi";
    private static final String CID_V1_RAW_BASE58 =
            "zb2rhk6GMPQF3hg1L6zHijYWHRbhuZTDxV9SgdHRdYVPv8hsF";

    @Test
    void validatesAndNormalizesKnownCidV0VectorToCidV1() {
        CanonicalCid cid = CanonicalCid.fromText(CID_V0);

        assertThat(cid.version()).isEqualTo(1);
        assertThat(cid.codec()).isEqualTo(CanonicalCid.DAG_PB_CODEC);
        assertThat(cid.multihashCode()).isEqualTo(CanonicalCid.SHA2_256_MULTIHASH);
        assertThat(cid.digestLength()).isEqualTo(32);
        assertThat(hex(cid.bytes())).isEqualTo(CID_V0_NORMALIZED_HEX);
        assertThat(cid.toString()).isEqualTo(CID_V0_NORMALIZED_BASE32);
        assertThat(cid.toBase32Text()).isEqualTo(CID_V0_NORMALIZED_BASE32);
        assertThat(cid.toBase58Text()).isEqualTo(CID_V0_NORMALIZED_BASE58);
    }

    @Test
    void parsesKnownCidV1DagPbInBothSupportedBases() {
        CanonicalCid base32 = CanonicalCid.fromText(CID_V1_DAG_PB);
        CanonicalCid base58 = CanonicalCid.fromText(CID_V1_DAG_PB_BASE58);

        assertThat(base32).isEqualTo(base58);
        assertThat(base32.version()).isEqualTo(1);
        assertThat(base32.codec()).isEqualTo(0x70);
        assertThat(base32.multihashCode()).isEqualTo(0x12);
        assertThat(base32.digestLength()).isEqualTo(32);
        assertThat(hex(base32.bytes())).isEqualTo(CID_V1_DAG_PB_HEX);
        assertThat(base32.toBase32Text()).isEqualTo(CID_V1_DAG_PB);
        assertThat(base32.toBase58Text()).isEqualTo(CID_V1_DAG_PB_BASE58);
        assertThat(base58.toString()).isEqualTo(CID_V1_DAG_PB);
    }

    @Test
    void parsesKnownCidV1RawVector() {
        CanonicalCid cid = CanonicalCid.parse(CID_V1_RAW_BASE58);

        assertThat(cid.codec()).isEqualTo(0x55);
        assertThat(cid.multihashCode()).isEqualTo(0x12);
        assertThat(cid.digestLength()).isEqualTo(32);
        assertThat(cid.canonicalText()).isEqualTo(CID_V1_RAW);
    }

    @Test
    void roundTripsCanonicalMultiByteVarints() {
        byte[] digest = new byte[64];
        Arrays.fill(digest, (byte) 0xa5);
        byte[] bytes = concat(
                bytes(0x01),
                bytes(0x81, 0x02), // codec 257
                bytes(0xd6, 0x02), // multihash code 342
                bytes(0x40),
                digest);

        CanonicalCid cid = CanonicalCid.fromBytes(bytes);

        assertThat(cid.codec()).isEqualTo(257);
        assertThat(cid.multihashCode()).isEqualTo(342);
        assertThat(cid.digestLength()).isEqualTo(64);
        assertThat(CanonicalCid.fromText(cid.toString())).isEqualTo(cid);
        assertThat(CanonicalCid.fromText(cid.toBase58Text())).isEqualTo(cid);
        assertThat(CanonicalCid.fromBytes(cid.toBytes())).isEqualTo(cid);
    }

    @Test
    void protectsAllBinaryStateWithDefensiveCopies() {
        byte[] input = unhex(CID_V1_DAG_PB_HEX);
        CanonicalCid cid = CanonicalCid.fromBytes(input);

        input[0] = 2;
        byte[] returnedBytes = cid.bytes();
        returnedBytes[0] = 2;
        byte[] returnedDigest = cid.digest();
        returnedDigest[0] = 0;

        assertThat(cid.toString()).isEqualTo(CID_V1_DAG_PB);
        assertThat(cid.bytes()[0]).isEqualTo((byte) 1);
        assertThat(cid.digest()[0]).isEqualTo((byte) 0x9d);
    }

    @Test
    void equalityAndHashCodeUseCanonicalBytes() {
        CanonicalCid first = CanonicalCid.fromText(CID_V1_RAW);
        CanonicalCid second = CanonicalCid.fromText(CID_V1_RAW_BASE58);
        CanonicalCid other = CanonicalCid.fromText(CID_V1_DAG_PB);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotEqualTo(other);
    }

    @Test
    void rejectsNullEmptyAndOverlengthBinaryValues() {
        assertRejected(() -> CanonicalCid.fromBytes(null));
        assertRejected(() -> CanonicalCid.fromBytes(new byte[0]));
        assertRejected(() -> CanonicalCid.fromBytes(new byte[CanonicalCid.MAX_BINARY_LENGTH + 1]));
    }

    @Test
    void rejectsUnsupportedVersionsAndInvalidCodec() {
        assertInvalid(unhex(CID_V0_HEX));
        assertInvalid(bytes(0x00, 0x70, 0x12, 0x01, 0x00));
        assertInvalid(bytes(0x02, 0x70, 0x12, 0x01, 0x00));
        assertInvalid(bytes(0x01, 0x00, 0x12, 0x01, 0x00));
    }

    @Test
    void rejectsNonCanonicalAndTruncatedVarints() {
        assertInvalid(bytes(0x81, 0x00, 0x70, 0x12, 0x01, 0x00));
        assertInvalid(bytes(0x01, 0xf0, 0x00, 0x12, 0x01, 0x00));
        assertInvalid(bytes(0x01, 0x70, 0x92, 0x00, 0x01, 0x00));
        assertInvalid(bytes(0x01, 0x70, 0x12, 0x81, 0x00, 0x00));
        assertInvalid(bytes(0x01, 0x80));
        assertInvalid(bytes(0x01, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80));
    }

    @Test
    void rejectsTextWhoseDecodedCidUsesNonCanonicalVarints() {
        assertRejected(() -> CanonicalCid.fromText("bqeahaeqbaa"));
        assertRejected(() -> CanonicalCid.fromText("bahyaaeqbaa"));
    }

    @Test
    void rejectsInvalidDigestLengthsAndPayloadMismatch() {
        assertInvalid(bytes(0x01, 0x70, 0x12, 0x00));
        assertInvalid(bytes(0x01, 0x70, 0x12, 0x41));
        assertInvalid(bytes(0x01, 0x70, 0x12, 0x02, 0x01));
        assertInvalid(bytes(0x01, 0x70, 0x12, 0x01, 0x01, 0x02));

        byte[] malformedV0 = unhex(CID_V0_HEX);
        malformedV0[1] = 31;
        assertInvalid(malformedV0);
    }

    @Test
    void rejectsNullEmptyOverlengthAndUnsupportedTextForms() {
        assertRejected(() -> CanonicalCid.fromText(null));
        assertRejected(() -> CanonicalCid.fromText(""));
        assertRejected(() -> CanonicalCid.fromText("b"));
        assertRejected(() -> CanonicalCid.fromText("z"));
        assertRejected(() -> CanonicalCid.fromText("x".repeat(161)));
        assertRejected(() -> CanonicalCid.fromText(" " + CID_V0));
        assertRejected(() -> CanonicalCid.fromText(CID_V1_DAG_PB.substring(1)));
        assertRejected(() -> CanonicalCid.fromText("z" + CID_V0));
        assertRejected(() -> CanonicalCid.fromText(
                "QmFhr1pDAUQX91bv6iMjKGEZUCAcfFwBuPdHUzumytfiK7"));
    }

    @Test
    void rejectsNonCanonicalBase32Text() {
        assertRejected(() -> CanonicalCid.fromText(
                "b" + CID_V1_DAG_PB.substring(1).toUpperCase()));
        assertRejected(() -> CanonicalCid.fromText(CID_V1_DAG_PB + "="));
        assertRejected(() -> CanonicalCid.fromText("b0"));

        String nonZeroTrailingBits = CID_V1_DAG_PB.substring(0, CID_V1_DAG_PB.length() - 1) + "j";
        assertRejected(() -> CanonicalCid.fromText(nonZeroTrailingBits));
    }

    @Test
    void rejectsInvalidBase58Text() {
        assertRejected(() -> CanonicalCid.fromText(CID_V0 + "0"));
        assertRejected(() -> CanonicalCid.fromText("z" + CID_V1_RAW_BASE58 + "O"));
    }

    private static void assertInvalid(byte[] bytes) {
        assertRejected(() -> CanonicalCid.fromBytes(bytes));
    }

    private static void assertRejected(Runnable action) {
        assertThatExceptionOfType(ConnectorContractException.class)
                .isThrownBy(action::run)
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ConnectorErrorCode.INVALID_PAYLOAD);
                    assertThat(error).hasMessage(ConnectorErrorCode.INVALID_PAYLOAD.wireCode());
                });
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static byte[] concat(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] unhex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }
}
