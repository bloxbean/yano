package org.yanoproject.api.appchain.transition;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One scalar fact that a transition kernel can establish by its own verification and expose to
 * declarative admission rules after an approved decision.
 *
 * <p>A fact is a kernel-owned output of verification, never an input: no binding, command,
 * configuration, or other plugin can supply one. Declaring a fact grants no authority; it only lets a
 * deployment rule deny a command the kernel already approved. The host neither evaluates rules nor
 * interprets facts; it only carries declarations and values across the plugin boundary.
 *
 * @param name identifier, {@code [a-zA-Z][a-zA-Z0-9_]{0,62}} and not a CEL reserved word, readable by rules as
 *             {@code facts.<name>}
 * @param type value type; {@link Type#TEXT_SET} values are sorted, duplicate-free text lists
 */
public record RuleFact(String name, Type type) {
    /** Frozen value types. Do not reorder: tooling exports the ordinals. */
    public enum Type { INTEGER, TEXT, BYTES, BOOLEAN, TEXT_SET }

    /** Maximum declared facts per kernel. */
    public static final int MAX_FACTS = 32;
    /** Maximum entries in one {@link Type#TEXT_SET} value. */
    public static final int MAX_SET_ENTRIES = 64;
    /** Maximum UTF-8 bytes of one {@link Type#TEXT_SET} entry. */
    public static final int MAX_SET_ENTRY_BYTES = 128;
    /** Maximum UTF-8 bytes of a {@link Type#TEXT} value, and maximum length of a {@link Type#BYTES} value. */
    public static final int MAX_VALUE_BYTES = 4096;
    /** CEL reserved words match the identifier grammar but cannot be selected as {@code facts.<name>}. */
    public static final Set<String> RESERVED_NAMES = Set.of("in", "as", "break", "const", "continue", "else",
            "false", "for", "function", "if", "import", "let", "loop", "namespace", "null", "package", "return",
            "true", "var", "void", "while");

    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,62}");

    public RuleFact {
        if (name == null || !NAME.matcher(name).matches() || RESERVED_NAMES.contains(name)) {
            throw new IllegalArgumentException("invalid rule fact name");
        }
        Objects.requireNonNull(type, "type");
    }
}
