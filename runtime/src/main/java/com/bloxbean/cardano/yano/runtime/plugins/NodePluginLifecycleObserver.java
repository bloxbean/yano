package com.bloxbean.cardano.yano.runtime.plugins;

/** Host-owned, observational view of actual {@code NodePlugin} lifecycle outcomes. */
interface NodePluginLifecycleObserver {
    NodePluginLifecycleObserver NOOP = new NodePluginLifecycleObserver() { };

    default void starting(String bundleId) {
    }

    default void started(String bundleId) {
    }

    default void startFailed(String bundleId) {
    }

    default void stopped(String bundleId, boolean succeeded) {
    }

    default void closed(String bundleId, boolean succeeded) {
    }
}
