package com.bloxbean.cardano.yano.api.plugin.operations;

/** Read-only cached node-local operations state; {@link #snapshot()} never invokes plugin code. */
@FunctionalInterface
public interface PluginOperationsView {
    PluginOperationsSnapshot snapshot();
}
