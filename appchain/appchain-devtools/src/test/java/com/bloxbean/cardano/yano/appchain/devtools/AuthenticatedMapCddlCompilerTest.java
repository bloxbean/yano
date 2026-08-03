package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapCddlCompilerTest {

    @Test
    void compilesNamedRulesMapsRangesSizesChoicesAndOptionalFields() {
        String source = """
                root = {
                  id: short-text,
                  count: 1..10,
                  ? ok: bool,
                  state: "active" / "held"
                }
                short-text = tstr .size (1..8)
                """;

        AuthenticatedMapCddlCompiler.Compilation compilation =
                AuthenticatedMapCddlCompiler.compile(source);

        assertThat(compilation.authoringLanguage()).isEqualTo("cddl-yano-subset-v1");
        assertThat(compilation.irCatalog()).isEqualTo("yano-cbor-schema-ir-v1");
        assertThat(compilation.schema().accepts(hex(
                "a46269646161626f6bf565636f756e740565737461746566616374697665")))
                .isTrue();
        assertThat(compilation.schema().accepts(hex(
                "a36269646065636f756e74056573746174656468656c64"))).isFalse();
        assertThat(compilation.schema().accepts(hex(
                "a3626964616165636f756e740b6573746174656468656c64"))).isFalse();
    }

    @Test
    void compilesBoundedArrayOccurrencesWithoutBacktrackingAmbiguity() {
        AuthenticatedMapCddlCompiler.Compilation compilation =
                AuthenticatedMapCddlCompiler.compile("""
                        root = [? uint, 1*3 label]
                        label = tstr .size (1..4)
                        """);

        assertThat(compilation.schema().accepts(hex("816178"))).isTrue();
        assertThat(compilation.schema().accepts(hex("82016178"))).isTrue();
        assertThat(compilation.schema().accepts(hex("846161616261636164"))).isFalse();
    }

    @Test
    void canonicalIrDoesNotDependOnMapOrChoiceSourceOrder() {
        byte[] first = AuthenticatedMapCddlCompiler.compile("""
                root = {alpha: uint, beta: tstr, state: "a" / "b"}
                """).definition();
        byte[] second = AuthenticatedMapCddlCompiler.compile("""
                root = {state: "b" / "a", beta: tstr, alpha: uint}
                """).definition();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsRecursiveUnboundedAndHostDependentCddl() {
        assertThatThrownBy(() -> AuthenticatedMapCddlCompiler.compile("""
                root = child
                child = root
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recursive");
        assertThatThrownBy(() -> AuthenticatedMapCddlCompiler.compile(
                "root = [* tstr]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthenticatedMapCddlCompiler.compile(
                "root = tstr .regexp \".*\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported CDDL control");
        assertThatThrownBy(() -> AuthenticatedMapCddlCompiler.compile(
                "root = external<thing>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAnExplicitlyDeclaredRootAndCanonicalNumbers() {
        assertThatThrownBy(() -> AuthenticatedMapCddlCompiler.compile(
                "record = uint", "root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root rule");
        assertThatThrownBy(() -> AuthenticatedMapCddlCompiler.compile(
                "root = uint .le 01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical decimal");
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
