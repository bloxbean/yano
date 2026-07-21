package com.bloxbean.cardano.yano.api.plugin.operations;

/** Manifest-only ServiceLoader factory for one bundle-owned health source. */
public interface PluginHealthProvider {
    /** Stable id equal to the manifest contribution name and owning bundle id. */
    String id();

    /** Creates one lifecycle-owned health source. */
    PluginHealthSource create(PluginHealthContext context);
}
