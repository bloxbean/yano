package org.yanoproject.api.appchain.transition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One namespace of a kernel's state, with the typed fields its stored values decode to, for declarative
 * admission-rule reads (Yano X ADR-031.4).
 *
 * <p>A view is a kernel-owned decoding of the kernel's own stored records. Rules read a declared field as
 * {@code reads.<read>.<field>} and a value field as {@code reads.<read>.value.<field>}; {@code present} is always
 * defined by the caller and is therefore reserved. Declaring a view grants no authority and gives no rule a way to
 * write, scan, or iterate state: every read is one exact key. The host neither evaluates rules nor interprets state;
 * it only carries declarations and bounded values across the plugin boundary.
 *
 * <p>The same value fields type the {@code value.<field>} entries of a write-view element
 * ({@link TransitionKernel#ruleWrites}): {@code value.<f>} is declared for writes when some view declares value
 * field {@code f} and every view that declares it gives it the same type.
 *
 * @param namespace {@code ""} for a single-namespace kernel, otherwise {@code [a-z0-9][a-z0-9._-]{0,63}}
 * @param fields fields every stored value of the namespace decodes to; at most {@link #MAX_FIELDS}, names unique,
 *               never {@code present}, and never {@code value} when {@code valueFields} is non-empty
 * @param valueFields fields of a typed application value, read under {@code value.<name>}; at most
 *                    {@link #MAX_FIELDS}, names unique. Any name is allowed, including {@code present} and
 *                    {@code value}: under the {@code value.} prefix they collide with nothing
 */
public record RuleValueView(String namespace, List<RuleFact> fields, List<RuleFact> valueFields) {
    /** Maximum declared views (namespaces) per kernel. */
    public static final int MAX_VIEWS = 64;
    /** Maximum declared fields per list, the same bound as kernel facts. */
    public static final int MAX_FIELDS = RuleFact.MAX_FACTS;
    /** Maximum elements of one command's write view ({@link TransitionKernel#ruleWrites}). */
    public static final int MAX_WRITES = 128;
    /** Prefix of value-field keys in {@link TransitionKernel#ruleValueFields} and write-view elements. */
    public static final String VALUE_PREFIX = "value.";
    /** The field name the caller defines for every read; no view may declare it. */
    public static final String PRESENT = "present";

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public RuleValueView {
        Objects.requireNonNull(namespace, "namespace");
        if (!namespace.isEmpty() && !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid rule value namespace");
        }
        fields = List.copyOf(fields);
        valueFields = List.copyOf(valueFields);
        requireFields(fields, true);
        requireFields(valueFields, false);
        if (!valueFields.isEmpty() && fields.stream().anyMatch(field -> field.name().equals("value"))) {
            throw new IllegalArgumentException("a view with value fields cannot declare a field named value");
        }
    }

    private static void requireFields(List<RuleFact> declared, boolean plain) {
        if (declared.size() > MAX_FIELDS) throw new IllegalArgumentException("too many rule value fields");
        Set<String> names = new HashSet<>();
        for (RuleFact field : declared) {
            if (!names.add(field.name()) || plain && PRESENT.equals(field.name())) {
                throw new IllegalArgumentException("duplicate or reserved rule value field");
            }
        }
    }
}
