package com.bloxbean.cardano.yano.api.plugin;

/** Scope and method used for runtime-owned plugin byte evidence. */
public enum PluginDigestMode {
    /** Complete bytes of one JAR artifact. */
    JAR,
    /** Canonical relative names and bytes of one exploded artifact tree. */
    ARTIFACT_TREE,
    /** Canonical complete-artifact closure used by one build-time bundle. */
    ARTIFACT_CLOSURE,
    /** Defining provider class bytes for an unindexed legacy provider. */
    LEGACY_CLASS
}
