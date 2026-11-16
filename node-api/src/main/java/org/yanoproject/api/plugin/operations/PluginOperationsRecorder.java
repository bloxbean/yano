package org.yanoproject.api.plugin.operations;

/**
 * Records product operations of contribution kinds whose operations are tracked.
 *
 * <p>One recorder is bound to one host instance. It accepts events only for
 * bundles that own a contribution of a tracked kind and ignores the rest.</p>
 */
public interface PluginOperationsRecorder {
    /**
     * A tracked contribution starts activating.
     *
     * @param kind contribution kind key
     * @param bundleId owning bundle id
     */
    void activating(String kind, String bundleId);

    /**
     * A tracked contribution became active.
     *
     * @param kind contribution kind key
     * @param bundleId owning bundle id
     */
    void active(String kind, String bundleId);

    /**
     * A tracked contribution failed to activate.
     *
     * @param kind contribution kind key
     * @param bundleId owning bundle id
     */
    void activationFailed(String kind, String bundleId);

    /**
     * A tracked contribution stopped.
     *
     * @param kind contribution kind key
     * @param bundleId owning bundle id
     * @param succeeded whether its products closed cleanly
     */
    void stopped(String kind, String bundleId, boolean succeeded);

    /**
     * One request was admitted to the bundle's queue.
     *
     * @param bundleId owning bundle id
     */
    void admitted(String bundleId);

    /**
     * One queued request entered plugin code.
     *
     * @param bundleId owning bundle id
     */
    void started(String bundleId);

    /**
     * One queued request left the queue without entering plugin code.
     *
     * @param bundleId owning bundle id
     */
    void queueFinished(String bundleId);

    /**
     * One request that entered plugin code returned.
     *
     * @param bundleId owning bundle id
     */
    void callbackFinished(String bundleId);

    /**
     * Counts one product request outcome.
     *
     * @param bundleId owning bundle id
     * @param operation {@link PluginOperation#DOMAIN_GET} or {@link PluginOperation#DOMAIN_POST}
     * @param outcome outcome
     * @throws IllegalArgumentException for any other operation, which the host records itself
     */
    void outcome(String bundleId, PluginOperation operation, PluginOperationOutcome outcome);
}
