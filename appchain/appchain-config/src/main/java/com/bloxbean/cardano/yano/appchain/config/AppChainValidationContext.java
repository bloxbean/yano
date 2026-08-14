package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.util.Map;
import java.util.Objects;

/** Immutable input for a trusted built-in or custom semantic validator. */
public record AppChainValidationContext(
        String chainPath,
        AppChainConfig config,
        Map<String, String> settings) {

    public AppChainValidationContext {
        chainPath = Objects.requireNonNull(chainPath, "chainPath");
        config = Objects.requireNonNull(config, "config");
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
