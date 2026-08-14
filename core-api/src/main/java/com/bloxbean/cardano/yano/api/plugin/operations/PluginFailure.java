package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Objects;

/** Redacted node-local failure containing no plugin-controlled diagnostic text. */
public record PluginFailure(PluginFailureCode code, long observedAtEpochMillis) {
    public PluginFailure {
        code = Objects.requireNonNull(code, "code");
        PluginOperationsValidation.nonNegative(
                observedAtEpochMillis, "observedAtEpochMillis");
        if (code == PluginFailureCode.NONE && observedAtEpochMillis != 0) {
            throw new IllegalArgumentException("NONE failure must use timestamp zero");
        }
        if (code != PluginFailureCode.NONE && observedAtEpochMillis == 0) {
            throw new IllegalArgumentException("a recorded failure must have a positive timestamp");
        }
    }

    public static PluginFailure none() {
        return new PluginFailure(PluginFailureCode.NONE, 0);
    }
}
