package com.bloxbean.cardano.yano.appchain.config;

import java.util.Objects;

/** Effective value and redaction-safe source metadata produced by resolved-mode loading. */
public record EffectiveConfigValue(
        String key,
        String value,
        String source,
        ConfigSourceKind sourceKind,
        int sourceOrdinal,
        boolean explicit,
        String profile) {

    public EffectiveConfigValue {
        key = Objects.requireNonNull(key, "key");
        value = Objects.requireNonNull(value, "value");
        source = Objects.requireNonNull(source, "source");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        profile = profile == null ? "" : profile;
    }
}
