package com.bloxbean.cardano.yano.appchain.config;

/** Stable provenance class for one effective configuration value. */
public enum ConfigSourceKind {
    DECLARED_FILE,
    ENVIRONMENT,
    SYSTEM_PROPERTIES,
    RUNTIME_DEFAULT,
    RUNTIME_DERIVED
}
