package com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalValueCborTest {

    @Test
    void acceptsPreferredScalarsStringsContainersAndFloats() {
        assertAccepted(
                "00", "17", "1818", "190100", "1a00010000",
                "1b0000000100000000", "20", "3818",
                "40", "420102", "60", "626869",
                "83010203", "a20a00616101", "a2616101616202",
                "f4", "f5", "f6", "f93e00", "f97e00",
                "fa47c35000", "fb400921fb54442d18");
    }

    @Test
    void rejectsNonPreferredArgumentsIndefiniteFormsAndTrailingItems() {
        assertRejected(
                "1817", "1900ff", "1a0000ffff", "1b00000000ffffffff",
                "5f4101ff", "7f6161ff", "9f01ff", "bf0102ff",
                "0001", "61ff");
    }

    @Test
    void rejectsUnsortedOrDuplicateMapKeys() {
        assertRejected(
                "a2616202616101",
                "a2616101616102",
                "a26161010a00");
    }

    @Test
    void rejectsTagsUndefinedUnassignedSimpleValuesAndNonPreferredFloats() {
        assertRejected(
                "c100", "f7", "f800",
                "f97e01", "f9fe00",
                "fa3fc00000", "fa7f800000", "fa7fc00000",
                "fb3ff8000000000000", "fb40f86a0000000000",
                "fb7ff8000000000000");
    }

    @Test
    void enforcesByteAndDepthBounds() {
        byte[] nested = new byte[CanonicalValueCbor.MAX_DEPTH + 2];
        Arrays.fill(nested, 0, nested.length - 1, (byte) 0x81);
        nested[nested.length - 1] = 0;

        assertThat(CanonicalValueCbor.accepts(hex("00"), 1)).isTrue();
        assertThat(CanonicalValueCbor.accepts(hex("00"), 0)).isFalse();
        assertThat(CanonicalValueCbor.accepts(hex("1818"), 1)).isFalse();
        assertThat(CanonicalValueCbor.accepts(new byte[0], 1)).isFalse();
        assertThat(CanonicalValueCbor.accepts(nested, nested.length)).isFalse();
    }

    private static void assertAccepted(String... values) {
        for (String value : values) {
            byte[] bytes = hex(value);
            assertThat(CanonicalValueCbor.accepts(bytes, bytes.length))
                    .as("canonical CBOR %s", value)
                    .isTrue();
        }
    }

    private static void assertRejected(String... values) {
        for (String value : values) {
            byte[] bytes = hex(value);
            assertThat(CanonicalValueCbor.accepts(bytes, bytes.length))
                    .as("non-canonical CBOR %s", value)
                    .isFalse();
        }
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
