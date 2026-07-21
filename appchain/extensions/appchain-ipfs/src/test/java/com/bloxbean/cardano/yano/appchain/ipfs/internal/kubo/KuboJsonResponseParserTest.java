package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class KuboJsonResponseParserTest {
    private static final String RAW_CID =
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi";
    private static final CanonicalCid CID = CanonicalCid.fromText(RAW_CID);

    @Test
    void decodesJsonEscapesRawUtf8AndAnySchemaFieldOrder() {
        String pinState = " \r\n{\"K\\u0065ys\":{\"\\u0062" + RAW_CID.substring(1)
                + "\":{\"Name\":\"\",\"Ty\\u0070e\":\"recurs\\u0069ve\"}}}\t";
        assertThat(KuboJsonResponseParser.parsePinState(utf8(pinState), CID))
                .isEqualTo(PinState.RECURSIVE);

        String acknowledgement = "{\"P\\u0069ns\":[\"\\u0062"
                + RAW_CID.substring(1) + "\"]}";
        KuboJsonResponseParser.parseAddAcknowledgement(utf8(acknowledgement), CID);

        String error = "{\"Type\":\"err\\u006fr\",\"Message\":\"café ☃ "
                + "\\uD83D\\uDE00\",\"Code\":-0}";
        assertThat(KuboJsonResponseParser.parseError(utf8(error)))
                .isEqualTo("café ☃ 😀");
    }

    @Test
    void rejectsDuplicateFieldsEvenWhenTheirNamesUseDifferentEscapes() {
        List<String> malformed = List.of(
                "{\"Message\":\"x\",\"M\\u0065ssage\":\"y\",\"Code\":0,"
                        + "\"Type\":\"error\"}",
                "{\"Keys\":{\"" + RAW_CID + "\":{\"Type\":\"direct\","
                        + "\"Ty\\u0070e\":\"recursive\"}}}",
                "{\"Pins\":[\"" + RAW_CID + "\"],\"P\\u0069ns\":[]}");

        assertRejected(() -> KuboJsonResponseParser.parseError(utf8(malformed.get(0))));
        assertRejected(() -> KuboJsonResponseParser.parsePinState(utf8(malformed.get(1)), CID));
        assertRejected(() -> KuboJsonResponseParser.parseAddAcknowledgement(
                utf8(malformed.get(2)), CID));
    }

    @Test
    void rejectsInvalidAndNonScalarUtf8WithoutReplacementCharacters() {
        List<byte[]> invalidSequences = List.of(
                bytes(0x80),
                bytes(0xC0, 0xAF),
                bytes(0xE0, 0x80, 0x80),
                bytes(0xED, 0xA0, 0x80),
                bytes(0xF0, 0x80, 0x80, 0x80),
                bytes(0xF4, 0x90, 0x80, 0x80),
                bytes(0xC2));

        for (byte[] invalid : invalidSequences) {
            assertRejected(() -> KuboJsonResponseParser.parseError(errorWithRawMessage(invalid)));
        }
        assertRejected(() -> KuboJsonResponseParser.parseError(errorWithRawMessage(bytes(0x00))));
        assertRejected(() -> KuboJsonResponseParser.parseError(errorWithRawMessage(bytes(0x1F))));
    }

    @Test
    void rejectsInvalidEscapesAndUnpairedUnicodeSurrogates() {
        for (String message : List.of(
                "\\x20",
                "\\uD800",
                "\\uDC00",
                "\\uD800\\u0041",
                "\\uD800x",
                "\\u12G4")) {
            String body = "{\"Message\":\"" + message
                    + "\",\"Code\":0,\"Type\":\"error\"}";
            assertRejected(() -> KuboJsonResponseParser.parseError(utf8(body)));
        }
    }

    @Test
    void acceptsOnlyTheIntegralZeroErrorCode() {
        assertThat(KuboJsonResponseParser.parseError(utf8(
                "{\"Message\":\"ok\",\"Code\":0,\"Type\":\"error\"}")))
                .isEqualTo("ok");
        assertThat(KuboJsonResponseParser.parseError(utf8(
                "{\"Message\":\"ok\",\"Code\":-0,\"Type\":\"error\"}")))
                .isEqualTo("ok");

        for (String code : List.of(
                "1", "-1", "00", "-00", "0.0", "0e0", "+0",
                "1234567890123", "\"0\"", "null")) {
            String body = "{\"Message\":\"bad\",\"Code\":" + code
                    + ",\"Type\":\"error\"}";
            assertRejected(() -> KuboJsonResponseParser.parseError(utf8(body)));
        }
    }

    @Test
    void enforcesStringAndDocumentBoundsBeforeReturningProviderData() {
        String bounded = "x".repeat(512);
        assertThat(KuboJsonResponseParser.parseError(utf8(
                "{\"Message\":\"" + bounded + "\",\"Code\":0,\"Type\":\"error\"}")))
                .isEqualTo(bounded);

        String tooLong = "{\"Message\":\"" + "x".repeat(513)
                + "\",\"Code\":0,\"Type\":\"error\"}";
        assertRejected(() -> KuboJsonResponseParser.parseError(utf8(tooLong)));
        assertRejected(() -> KuboJsonResponseParser.parseError(new byte[16 * 1_024 + 1]));
    }

    private static byte[] errorWithRawMessage(byte[] rawMessage) {
        byte[] prefix = utf8("{\"Message\":\"");
        byte[] suffix = utf8("\",\"Code\":0,\"Type\":\"error\"}");
        byte[] body = new byte[prefix.length + rawMessage.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(rawMessage, 0, body, prefix.length, rawMessage.length);
        System.arraycopy(suffix, 0, body, prefix.length + rawMessage.length, suffix.length);
        return body;
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRejected(ThrowingCall call) {
        assertThatExceptionOfType(IpfsProviderException.class)
                .isThrownBy(call::run)
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo(ConnectorErrorCode.PROVIDER_REJECTED);
                    assertThat(failure.getCause()).isNull();
                });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
