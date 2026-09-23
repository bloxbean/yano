package org.yanoproject.api.appchain.transition;

import java.util.List;
import java.util.Objects;

/**
 * Data-only command wire layout used to validate and encode declarative mappings.
 * Positional fields are encoded in declaration order, never alphabetically. A descriptor documents a wire
 * command; it does not replace decoding, admission, signature verification, or transition authorization.
 *
 * @param commandName stable command name within the kernel
 * @param layout wire container shape
 * @param opCode leading integer used only by {@link Layout#ARRAY_WITH_OPCODE}
 * @param fields ordered scalar fields, each marked as application data or externally supplied evidence
 */
public record CommandDescriptor(String commandName, Layout layout, long opCode, List<Field> fields) {
    /** An opcode-prefixed array, text-keyed map, uninterpreted byte payload, or opcode-free array. */
    public enum Layout { ARRAY_WITH_OPCODE, MAP, RAW_BYTES, ARRAY }
    /** Evidence may be forwarded from an event field but must not be synthesized by binding calculations. */
    public enum Role { DATA, EVIDENCE }

    /**
     * One scalar field. Optionality does not imply that a positional array slot can be omitted;
     * the selected command layout determines whether omission has a wire representation.
     */
    public record Field(String name, TransitionScalars.Type type, boolean required, Role role) {
        public Field {
            requireName(name);
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(role, "role");
        }
    }

    public CommandDescriptor {
        requireName(commandName);
        Objects.requireNonNull(layout, "layout");
        fields = List.copyOf(fields);
        if (fields.size() > 64 || fields.stream().map(Field::name).distinct().count() != fields.size()) {
            throw new IllegalArgumentException("invalid command fields");
        }
        if (layout == Layout.RAW_BYTES && !fields.isEmpty()) {
            throw new IllegalArgumentException("raw command cannot declare fields");
        }
    }

    static void requireName(String name) {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,126}")) {
            throw new IllegalArgumentException("invalid descriptor name");
        }
    }
}
