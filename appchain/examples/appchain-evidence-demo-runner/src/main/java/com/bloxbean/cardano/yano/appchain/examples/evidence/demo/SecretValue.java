package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.util.Objects;

/** A deliberately non-printing in-memory secret value. */
public final class SecretValue {
    private final String value;

    SecretValue(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "<redacted>";
    }
}
