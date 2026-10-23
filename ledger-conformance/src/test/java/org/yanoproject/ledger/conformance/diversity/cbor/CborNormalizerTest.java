package org.yanoproject.ledger.conformance.diversity.cbor;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.HexFormat;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The 20 vectors of cardano-cbor-dataset's {@code dataset/normalization/README.md}, the table being the authority. */
class CborNormalizerTest {

    /** name, input, normalized; the last row, beyond the table, is a rational being reduced. */
    private static final String[] VECTORS = {
            "indefinite-array, 9f0102ff, 820102",
            "indefinite-map, bf0102ff, a10102",
            "chunked-bytes, 5f420102420304ff, 4401020304",
            "chunked-text, 7f626162626364ff, 6461626364",
            "nested-indefinite, 9f9f01ffbf0102ffff, 828101a10102",
            "empty-indef-array, 9fff, 80",
            "empty-indef-map, bfff, a0",
            "non-minimal-int, 1805, 05",
            "unsorted-map-keys, a202010102, a202010102",
            "duplicate-map-keys, a201020103, a201020103",
            "uint-2^64-1, 1bffffffffffffffff, 1bffffffffffffffff",
            "tag-30-rational, d81e820102, d81e820102",
            "tag-259-map, d90103a10102, d90103a10102",
            "unknown-tag-1295, d9050f01, d9050f01",
            "tag-over-indefinite, d81e9f0102ff, d81e820102",
            "float16, f93c00, f93c00",
            "bignum-fits, c24296be, 1996be",
            "negative-bignum-fits, c3435e0f31, 3a005e0f31",
            "bignum-leading-zeros, c2420000, 00",
            "bignum-too-large, c249010000000000000000, c249010000000000000000",
            "reduced-rational, d81e820204, d81e820102"
    };

    @TestFactory
    Stream<DynamicTest> normalizes() {
        return Stream.of(VECTORS).map(row -> row.split(",\\s*")).map(v -> DynamicTest.dynamicTest(v[0], () ->
                assertThat(HexFormat.of().formatHex(CborNormalizer.normalize(HexFormat.of().parseHex(v[1]))))
                        .isEqualTo(v[2])));
    }
}
