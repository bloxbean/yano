package org.yanoproject.api.appchain.schema;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CborSchemaTest {

    @Test
    void canonicalIrRoundTripsAndCanonicalizesUnorderedNodes() {
        CborSchema.Node integer = new CborSchema.IntegerNode(
                CborSchema.INTEGER_UINT,
                BigInteger.ONE, BigInteger.valueOf(100));
        CborSchema.Node text = new CborSchema.TextNode(1, 8, null);

        byte[] first = CborSchema.of(new CborSchema.ChoiceNode(
                List.of(text, integer))).definition();
        byte[] second = CborSchema.of(new CborSchema.ChoiceNode(
                List.of(integer, text))).definition();

        assertThat(first).isEqualTo(second);
        assertThat(CborSchema.decode(first).definition()).isEqualTo(first);

        CborSchema.MapNode unordered = new CborSchema.MapNode(List.of(
                new CborSchema.MapField("sku", true, text),
                new CborSchema.MapField("qty", true, integer)));
        CborSchema.MapNode reversed = new CborSchema.MapNode(List.of(
                new CborSchema.MapField("qty", true, integer),
                new CborSchema.MapField("sku", true, text)));
        assertThat(CborSchema.of(unordered).definition())
                .isEqualTo(CborSchema.of(reversed).definition());
    }

    @Test
    void nestedChoicesAtTheDepthBoundCanonicalizeInLinearTime() {
        // 15 nested choices are the deepest IR the 32-level CBOR bound admits (two levels per choice)
        CborSchema.Node node = CborSchema.BooleanNode.any();
        for (int depth = 0; depth < 15; depth++) {
            node = new CborSchema.ChoiceNode(List.of(node, new CborSchema.NullNode()));
        }
        CborSchema.Node deepest = node;
        // Re-encoding every subtree per comparison took about 3^15 encodings here
        byte[] definition = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> CborSchema.of(deepest).definition());
        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> CborSchema.isCanonicalDefinition(definition))).isTrue();
    }

    @Test
    void nestedArraysOfManyTermsEvaluateTheSameOnASmallStack() throws Exception {
        // 7 arrays of 255 zero-occurrence terms and one required term: within every bound, but recursing
        // once per term overflowed a 256 KiB stack and turned a valid write into a refusal
        CborSchema.Node node = new CborSchema.NullNode();
        for (int level = 0; level < 7; level++) {
            List<CborSchema.Occurrence> terms = new ArrayList<>();
            for (int term = 0; term < 255; term++) {
                terms.add(new CborSchema.Occurrence(0, 0, new CborSchema.NullNode()));
            }
            terms.add(CborSchema.Occurrence.required(node));
            node = new CborSchema.ArrayNode(terms);
        }
        CborSchema.Schema schema = CborSchema.decode(CborSchema.of(node).definition());
        boolean[] accepted = new boolean[2];
        Thread small = new Thread(null, () -> {
            accepted[0] = schema.accepts(hex("81818181818181f6"));
            accepted[1] = schema.accepts(hex("81818181818181f5"));
        }, "small-stack", 256 * 1024);
        small.start();
        small.join();

        assertThat(accepted).containsExactly(true, false);
    }

    @Test
    void aTermOnlyEvaluatesItemsAReachableStartCanConsume() {
        // four required fields and a long tail: scanning the whole tail for each field exhausted the step budget
        List<CborSchema.Occurrence> terms = new ArrayList<>();
        for (int field = 0; field < 4; field++) {
            terms.add(CborSchema.Occurrence.required(CborSchema.IntegerNode.uint()));
        }
        terms.add(new CborSchema.Occurrence(0, 65_532, CborSchema.IntegerNode.uint()));
        CborSchema.Schema schema = CborSchema.of(new CborSchema.ArrayNode(terms));
        byte[] zeros = new byte[3 + 32_768]; // array(32768) of 0
        zeros[0] = (byte) 0x99;
        zeros[1] = (byte) 0x80;

        assertThat(schema.accepts(zeros)).isTrue();
        zeros[zeros.length - 1] = (byte) 0xf6; // a null in the tail
        assertThat(schema.accepts(zeros)).isFalse();
    }

    @Test
    void overlappingRepeatedTermsFindASplitWithinTheBudget() {
        // after a variable-length term ~1,000 positions are reachable; walking the next repeated term from each
        // one exhausted the budget, while the greedy search finds 999 + 1 + null at once
        CborSchema.Schema schema = CborSchema.of(new CborSchema.ArrayNode(List.of(
                new CborSchema.Occurrence(1, 1000, new CborSchema.IntegerNode(
                        CborSchema.INTEGER_UINT, BigInteger.ZERO, BigInteger.TEN)),
                new CborSchema.Occurrence(1, 1000, new CborSchema.IntegerNode(
                        CborSchema.INTEGER_UINT, BigInteger.ZERO, BigInteger.valueOf(20))),
                CborSchema.Occurrence.required(new CborSchema.NullNode()))));
        byte[] payload = new byte[3 + 1000 + 1]; // array(1001): 1,000 zeros, then null
        payload[0] = (byte) 0x99;
        payload[1] = 0x03;
        payload[2] = (byte) 0xe9;
        payload[payload.length - 1] = (byte) 0xf6;

        assertThat(schema.accepts(payload)).isTrue();
        payload[payload.length - 1] = (byte) 0xf5; // true is no null
        assertThat(schema.accepts(payload)).isFalse();
    }

    @Test
    void textWorkIsBoundedByTheStepBudget() throws Exception {
        // 255 overlapping text terms over 32 multi-byte texts of 1,900 bytes and no closing null: the search
        // tries each text many times; it is measured once, and every byte touched is charged to the budget
        List<CborSchema.Occurrence> terms = new ArrayList<>();
        for (int term = 0; term < 255; term++) {
            terms.add(new CborSchema.Occurrence(0, 32, new CborSchema.TextNode(0, 4096, null)));
        }
        terms.add(CborSchema.Occurrence.required(new CborSchema.NullNode()));
        CborSchema.Schema schema = CborSchema.of(new CborSchema.ArrayNode(terms));
        co.nstant.in.cbor.model.Array texts = new co.nstant.in.cbor.model.Array();
        for (int item = 0; item < 32; item++) {
            texts.add(new co.nstant.in.cbor.model.UnicodeString("\u00e9".repeat(950)));
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new co.nstant.in.cbor.CborEncoder(out).encode(texts);
        byte[] payload = out.toByteArray();

        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(1), () -> schema.accepts(payload))).isFalse();
        byte[] closed = Arrays.copyOf(payload, payload.length + 1);
        closed[0] = (byte) 0x98; // array(33): the same texts, then null
        closed[1] = 33;
        System.arraycopy(payload, 2, closed, 2, payload.length - 2);
        closed[closed.length - 1] = (byte) 0xf6;
        assertThat(schema.accepts(closed)).isTrue();
    }

    @Test
    void aLargeByteLiteralIsNotCopiedPerMatch() {
        // a 60,000-byte literal with broad bounds, tried against 8,192 one-byte strings: copying the literal
        // per attempt allocated about 1 GB for a 16 KiB payload, none of it charged to the budget
        CborSchema.Schema schema = CborSchema.of(new CborSchema.ArrayNode(List.of(
                new CborSchema.Occurrence(0, 8192, CborSchema.BytesNode.any()),
                CborSchema.Occurrence.required(new CborSchema.BytesNode(0, 60_000, new byte[60_000])))));
        byte[] payload = new byte[3 + 2 * 8192]; // array(8192) of h'00'
        payload[0] = (byte) 0x99;
        payload[1] = 0x20;
        for (int item = 0; item < 8192; item++) {
            payload[3 + 2 * item] = 0x41;
        }
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();

        assertThat(schema.accepts(payload)).isFalse();
        assertThat(threads.getCurrentThreadAllocatedBytes() - before).isLessThan(64L * 1024 * 1024);
    }

    @Test
    void evaluatesExactMapsBoundsChoicesAndOptionalFields() {
        CborSchema.Schema schema = productSchema();

        assertThat(schema.accepts(hex("a2637174790563736b756141"))).isTrue();
        assertThat(schema.accepts(hex(
                "a363717479186463736b75614264746167738261616162"))).isTrue();
        assertThat(schema.accepts(hex("a163736b756141"))).isFalse();
        assertThat(schema.accepts(hex("a3617801637174790563736b756141"))).isFalse();
        assertThat(schema.accepts(hex("a263717479186563736b756141"))).isFalse();
        assertThat(schema.accepts(hex("a2637174790563736b7569414243444546474849")))
                .isFalse();
    }

    @Test
    void boundedArrayMatchingHandlesOptionalAndRepeatedTerms() {
        CborSchema.Schema schema = CborSchema.of(
                new CborSchema.ArrayNode(List.of(
                        new CborSchema.Occurrence(
                                0, 1, CborSchema.IntegerNode.uint()),
                        CborSchema.Occurrence.required(
                                new CborSchema.TextNode(1, 4, null)))));

        assertThat(schema.accepts(hex("816178"))).isTrue();
        assertThat(schema.accepts(hex("82016178"))).isTrue();
        assertThat(schema.accepts(hex("8301026178"))).isFalse();
    }

    @Test
    void supportsTheCompleteNativeCborIntegerDomain() {
        CborSchema.Schema integers = CborSchema.of(
                CborSchema.IntegerNode.integer());

        assertThat(integers.accepts(hex("1bffffffffffffffff"))).isTrue();
        assertThat(integers.accepts(hex("3bffffffffffffffff"))).isTrue();
        assertThat(integers.accepts(hex("00"))).isTrue();
    }

    @Test
    void malformedIrAndValuesFailClosed() {
        CborSchema.Schema schema = productSchema();
        byte[] trailing = java.util.Arrays.copyOf(schema.definition(),
                schema.definition().length + 1);

        assertThatThrownBy(() -> CborSchema.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(schema.accepts(hex("bf63736b756141ff"))).isFalse();
        assertThat(schema.accepts(hex("a263736b756141637174791805"))).isFalse();

        Random random = new Random(25_001L);
        for (int index = 0; index < 2_000; index++) {
            byte[] input = new byte[random.nextInt(64)];
            random.nextBytes(input);
            assertThatCodeDoesNotEscape(schema, input);
        }
    }

    @Test
    void rejectsDuplicateChoiceAndMapDefinitions() {
        CborSchema.TextNode text = CborSchema.TextNode.any();
        assertThatThrownBy(() -> CborSchema.of(
                new CborSchema.ChoiceNode(List.of(text, text))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new CborSchema.MapNode(List.of(
                new CborSchema.MapField("x", true, text),
                new CborSchema.MapField("x", false, text))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void structuralAndOccurrenceBoundsFailBeforeRuntimeEvaluation() {
        CborSchema.Node nested = new CborSchema.NullNode();
        for (int depth = 0; depth < CborSchema.MAX_SCHEMA_DEPTH; depth++) {
            nested = new CborSchema.ArrayNode(List.of(
                    CborSchema.Occurrence.required(nested)));
        }
        CborSchema.Node tooDeep = nested;
        assertThatThrownBy(() -> CborSchema.of(tooDeep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");
        // within the node bound but beyond the IR's 32 CBOR levels: of() refuses what decode() would
        CborSchema.Node arrays = new CborSchema.NullNode();
        for (int depth = 0; depth < 11; depth++) {
            arrays = new CborSchema.ArrayNode(List.of(CborSchema.Occurrence.required(arrays)));
        }
        CborSchema.Node elevenArrays = arrays;
        assertThatThrownBy(() -> CborSchema.of(elevenArrays)).isInstanceOf(IllegalArgumentException.class);

        List<CborSchema.Node> choices = java.util.stream.IntStream
                .range(0, CborSchema.MAX_CHOICE_OPTIONS + 1)
                .mapToObj(index -> (CborSchema.Node)
                        CborSchema.TextNode.literal("choice-" + index))
                .toList();
        assertThatThrownBy(() -> new CborSchema.ChoiceNode(choices))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CborSchema.Occurrence(
                0, CborSchema.MAX_OCCURRENCE + 1,
                CborSchema.TextNode.any()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CborSchema.Schema productSchema() {
        CborSchema.ArrayNode tags = new CborSchema.ArrayNode(
                List.of(new CborSchema.Occurrence(0, 3,
                        new CborSchema.TextNode(1, 4, null))));
        return CborSchema.of(new CborSchema.MapNode(List.of(
                new CborSchema.MapField("sku", true,
                        new CborSchema.TextNode(1, 8, null)),
                new CborSchema.MapField("qty", true,
                        new CborSchema.IntegerNode(
                                CborSchema.INTEGER_UINT,
                                BigInteger.ONE, BigInteger.valueOf(100))),
                new CborSchema.MapField("tags", false, tags))));
    }

    private static void assertThatCodeDoesNotEscape(
            CborSchema.Schema schema,
            byte[] input
    ) {
        try {
            schema.accepts(input);
        } catch (RuntimeException | StackOverflowError escaped) {
            throw new AssertionError("schema evaluator escaped for bounded input", escaped);
        }
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
