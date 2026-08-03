package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapSchemaTest {

    @Test
    void canonicalIrRoundTripsAndCanonicalizesUnorderedNodes() {
        AuthenticatedMapSchema.Node integer = new AuthenticatedMapSchema.IntegerNode(
                AuthenticatedMapSchema.INTEGER_UINT,
                BigInteger.ONE, BigInteger.valueOf(100));
        AuthenticatedMapSchema.Node text = new AuthenticatedMapSchema.TextNode(1, 8, null);

        byte[] first = AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ChoiceNode(
                List.of(text, integer))).definition();
        byte[] second = AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ChoiceNode(
                List.of(integer, text))).definition();

        assertThat(first).isEqualTo(second);
        assertThat(AuthenticatedMapSchema.decode(first).definition()).isEqualTo(first);

        AuthenticatedMapSchema.MapNode unordered = new AuthenticatedMapSchema.MapNode(List.of(
                new AuthenticatedMapSchema.MapField("sku", true, text),
                new AuthenticatedMapSchema.MapField("qty", true, integer)));
        AuthenticatedMapSchema.MapNode reversed = new AuthenticatedMapSchema.MapNode(List.of(
                new AuthenticatedMapSchema.MapField("qty", true, integer),
                new AuthenticatedMapSchema.MapField("sku", true, text)));
        assertThat(AuthenticatedMapSchema.of(unordered).definition())
                .isEqualTo(AuthenticatedMapSchema.of(reversed).definition());
    }

    @Test
    void evaluatesExactMapsBoundsChoicesAndOptionalFields() {
        AuthenticatedMapSchema.Schema schema = productSchema();

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
        AuthenticatedMapSchema.Schema schema = AuthenticatedMapSchema.of(
                new AuthenticatedMapSchema.ArrayNode(List.of(
                        new AuthenticatedMapSchema.Occurrence(
                                0, 1, AuthenticatedMapSchema.IntegerNode.uint()),
                        AuthenticatedMapSchema.Occurrence.required(
                                new AuthenticatedMapSchema.TextNode(1, 4, null)))));

        assertThat(schema.accepts(hex("816178"))).isTrue();
        assertThat(schema.accepts(hex("82016178"))).isTrue();
        assertThat(schema.accepts(hex("8301026178"))).isFalse();
    }

    @Test
    void supportsTheCompleteNativeCborIntegerDomain() {
        AuthenticatedMapSchema.Schema integers = AuthenticatedMapSchema.of(
                AuthenticatedMapSchema.IntegerNode.integer());

        assertThat(integers.accepts(hex("1bffffffffffffffff"))).isTrue();
        assertThat(integers.accepts(hex("3bffffffffffffffff"))).isTrue();
        assertThat(integers.accepts(hex("00"))).isTrue();
    }

    @Test
    void malformedIrAndValuesFailClosed() {
        AuthenticatedMapSchema.Schema schema = productSchema();
        byte[] trailing = java.util.Arrays.copyOf(schema.definition(),
                schema.definition().length + 1);

        assertThatThrownBy(() -> AuthenticatedMapSchema.decode(trailing))
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
        AuthenticatedMapSchema.TextNode text = AuthenticatedMapSchema.TextNode.any();
        assertThatThrownBy(() -> AuthenticatedMapSchema.of(
                new AuthenticatedMapSchema.ChoiceNode(List.of(text, text))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new AuthenticatedMapSchema.MapNode(List.of(
                new AuthenticatedMapSchema.MapField("x", true, text),
                new AuthenticatedMapSchema.MapField("x", false, text))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void structuralAndOccurrenceBoundsFailBeforeRuntimeEvaluation() {
        AuthenticatedMapSchema.Node nested = new AuthenticatedMapSchema.NullNode();
        for (int depth = 0; depth < AuthenticatedMapSchema.MAX_SCHEMA_DEPTH; depth++) {
            nested = new AuthenticatedMapSchema.ArrayNode(List.of(
                    AuthenticatedMapSchema.Occurrence.required(nested)));
        }
        AuthenticatedMapSchema.Node tooDeep = nested;
        assertThatThrownBy(() -> AuthenticatedMapSchema.of(tooDeep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");

        List<AuthenticatedMapSchema.Node> choices = java.util.stream.IntStream
                .range(0, AuthenticatedMapSchema.MAX_CHOICE_OPTIONS + 1)
                .mapToObj(index -> (AuthenticatedMapSchema.Node)
                        AuthenticatedMapSchema.TextNode.literal("choice-" + index))
                .toList();
        assertThatThrownBy(() -> new AuthenticatedMapSchema.ChoiceNode(choices))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedMapSchema.Occurrence(
                0, AuthenticatedMapSchema.MAX_OCCURRENCE + 1,
                AuthenticatedMapSchema.TextNode.any()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AuthenticatedMapSchema.Schema productSchema() {
        AuthenticatedMapSchema.ArrayNode tags = new AuthenticatedMapSchema.ArrayNode(
                List.of(new AuthenticatedMapSchema.Occurrence(0, 3,
                        new AuthenticatedMapSchema.TextNode(1, 4, null))));
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.MapNode(List.of(
                new AuthenticatedMapSchema.MapField("sku", true,
                        new AuthenticatedMapSchema.TextNode(1, 8, null)),
                new AuthenticatedMapSchema.MapField("qty", true,
                        new AuthenticatedMapSchema.IntegerNode(
                                AuthenticatedMapSchema.INTEGER_UINT,
                                BigInteger.ONE, BigInteger.valueOf(100))),
                new AuthenticatedMapSchema.MapField("tags", false, tags))));
    }

    private static void assertThatCodeDoesNotEscape(
            AuthenticatedMapSchema.Schema schema,
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
