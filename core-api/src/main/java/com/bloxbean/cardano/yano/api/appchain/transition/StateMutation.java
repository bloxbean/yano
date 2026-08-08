package com.bloxbean.cardano.yano.api.appchain.transition;

import java.util.Objects;

/** One immutable authenticated-state write or deletion in a transition plan. */
public record StateMutation(Kind kind, byte[] key, byte[] value) {

    public enum Kind { PUT, DELETE }

    public StateMutation {
        kind = Objects.requireNonNull(kind, "kind");
        key = Objects.requireNonNull(key, "key").clone();
        if (key.length == 0) {
            throw new IllegalArgumentException("mutation key must not be empty");
        }
        if (kind == Kind.PUT) {
            value = Objects.requireNonNull(value, "PUT value").clone();
        } else if (value != null) {
            throw new IllegalArgumentException("DELETE must not carry a value");
        }
    }

    public static StateMutation put(byte[] key, byte[] value) {
        return new StateMutation(Kind.PUT, key, value);
    }

    public static StateMutation delete(byte[] key) {
        return new StateMutation(Kind.DELETE, key, null);
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public byte[] value() {
        return value != null ? value.clone() : null;
    }
}
