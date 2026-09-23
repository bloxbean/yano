package org.yanoproject.api.appchain.transition;

import java.util.Arrays;
import java.util.Objects;

/**
 * A component-owned, block-scoped work counter declared by its transition kernel.
 *
 * <p>The key is local to the owning component. Declaring a budget reserves that key exclusively for
 * {@link TransitionWorkAccounting}; ordinary transition mutations, receipts, and consumptions must not
 * overwrite it. Assemblers reject duplicate ids or keys and pin the declaration in committed configuration.
 * This contract grants no caller arbitrary write access to the owner.
 *
 * @param id stable budget identifier
 * @param key nonempty component-local key, at most {@link #MAX_KEY_BYTES} bytes
 * @param maximumUnits positive work-unit limit per block
 */
public record TransitionWorkBudget(String id, byte[] key, int maximumUnits) {
    /** Maximum number of declarations or references a kernel may expose. */
    public static final int MAX_DECLARATIONS = 256;
    /** Maximum size of an owned counter key, before component namespacing. */
    public static final int MAX_KEY_BYTES = 256;

    /** Validates and snapshots a declaration; subsequent caller mutations cannot change its key. */
    public TransitionWorkBudget {
        requireIdentifier(id);
        Objects.requireNonNull(key, "key");
        if (key.length == 0 || key.length > MAX_KEY_BYTES || maximumUnits < 1) {
            throw new IllegalArgumentException("invalid transition work budget");
        }
        key = key.clone();
    }

    /** Returns a defensive copy of the component-local counter key. */
    @Override public byte[] key() { return key.clone(); }

    /** Compares key contents rather than array identity. */
    @Override public boolean equals(Object other) {
        return other instanceof TransitionWorkBudget that && id.equals(that.id)
                && Arrays.equals(key, that.key) && maximumUnits == that.maximumUnits;
    }

    /** Computes a content-based hash consistent with {@link #equals(Object)}. */
    @Override public int hashCode() {
        return 31 * (31 * id.hashCode() + Arrays.hashCode(key)) + maximumUnits;
    }

    static void requireIdentifier(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,126}")) {
            throw new IllegalArgumentException("invalid transition work identifier");
        }
    }
}
