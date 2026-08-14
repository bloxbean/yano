package com.bloxbean.cardano.yano.runtime.plugins;

/**
 * Catalog-discovery contract selected by the runtime embedding.
 *
 * <p>Packaged JVM and native applications have a build-generated aggregate
 * index and therefore fail closed if it is absent or ambiguous. Library mode
 * retains the explicit migration path for older embedders that only provide
 * ServiceLoader entries.</p>
 */
public enum PluginDiscoveryMode {
    /** Backward-compatible library embedding; a missing aggregate index is allowed. */
    LIBRARY_COMPATIBILITY(false, true),
    /** Packaged JVM application; exactly one authoritative aggregate index is required. */
    PACKAGED_JVM(true, false),
    /** Native image; exactly one build-time aggregate index is required. */
    NATIVE(true, false);

    private final boolean requiresEmbeddedIndex;
    private final boolean allowsUnindexedLegacyProviders;

    PluginDiscoveryMode(boolean requiresEmbeddedIndex,
                        boolean allowsUnindexedLegacyProviders) {
        this.requiresEmbeddedIndex = requiresEmbeddedIndex;
        this.allowsUnindexedLegacyProviders = allowsUnindexedLegacyProviders;
    }

    boolean requiresEmbeddedIndex() {
        return requiresEmbeddedIndex;
    }

    boolean allowsUnindexedLegacyProviders() {
        return allowsUnindexedLegacyProviders;
    }
}
