package com.bloxbean.cardano.yano.api.appchain.codec.internal;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class CborStructurePreflightTest {

    @Test
    void acceptsBoundedIndefiniteByteAndTextStringsProducedByCanonicalDataCodecs() {
        assertThat(accepts("5f4201024103ff")).isTrue();
        assertThat(accepts("7f6268696121ff")).isTrue();
        assertThat(accepts("9f5f4101ff7f6161ffff")).isTrue();
    }

    @Test
    void rejectsWrongOrNestedStringChunksMissingBreakAndTrailingData() {
        assertThat(accepts("5f6161ff")).isFalse();
        assertThat(accepts("5f5f4101ffff")).isFalse();
        assertThat(accepts("7f7f6161ffff")).isFalse();
        assertThat(accepts("5f4101")).isFalse();
        assertThat(accepts("5f4101ff00")).isFalse();
        assertThat(accepts("bf01ff")).isFalse();
        assertThat(accepts("bf0102ff")).isTrue();
    }

    @Test
    void enforcesDepthItemAndByteBudgetsWithoutRecursion() {
        byte[] threeItems = HexFormat.of().parseHex("9f010203ff");

        assertThat(CborStructurePreflight.accepts(threeItems,
                threeItems.length, 2, 4)).isTrue();
        assertThat(CborStructurePreflight.accepts(threeItems,
                threeItems.length - 1, 2, 4)).isFalse();
        assertThat(CborStructurePreflight.accepts(threeItems,
                threeItems.length, 2, 3)).isFalse();
        assertThat(CborStructurePreflight.accepts(
                HexFormat.of().parseHex("9f9f01ffff"), 5, 1, 8)).isFalse();
    }

    @Test
    void enforcesContainerAndAggregateStringBudgetsForDefiniteAndIndefiniteForms() {
        var limits = new CborStructurePreflight.Limits(64, 8, 32, 2, 3);

        assertThat(CborStructurePreflight.accepts(
                HexFormat.of().parseHex("820102"), limits)).isTrue();
        assertThat(CborStructurePreflight.accepts(
                HexFormat.of().parseHex("83010203"), limits)).isFalse();
        assertThat(CborStructurePreflight.accepts(
                HexFormat.of().parseHex("4401020304"), limits)).isFalse();
        assertThat(CborStructurePreflight.accepts(
                HexFormat.of().parseHex("5f420102420304ff"), limits)).isFalse();
        assertThat(CborStructurePreflight.accepts(
                HexFormat.of().parseHex("9f010203ff"), limits)).isFalse();
    }

    @Test
    void acceptsFullWidthScalarsAndFloatsButRejectsIllegalBreaks() {
        assertThat(accepts("1bffffffffffffffff")).isTrue();
        assertThat(accepts("fb3ff0000000000000")).isTrue();
        assertThat(accepts("ff")).isFalse();
        assertThat(accepts("81ff")).isFalse();
    }

    private static boolean accepts(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        return CborStructurePreflight.accepts(bytes, bytes.length, 8, 32);
    }
}
