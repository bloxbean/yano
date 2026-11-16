package org.yanoproject.runtime.host;

import org.yanoproject.api.plugin.operations.PluginOperation;
import org.yanoproject.api.plugin.operations.PluginOperationOutcome;
import org.yanoproject.api.plugin.operations.PluginOperationsRecorder;

/** Test recorder that ignores every event. */
public final class NoopOperationsRecorder implements PluginOperationsRecorder {
    @Override public void activating(String kind, String bundleId) { }
    @Override public void active(String kind, String bundleId) { }
    @Override public void activationFailed(String kind, String bundleId) { }
    @Override public void stopped(String kind, String bundleId, boolean succeeded) { }
    @Override public void admitted(String bundleId) { }
    @Override public void started(String bundleId) { }
    @Override public void queueFinished(String bundleId) { }
    @Override public void callbackFinished(String bundleId) { }
    @Override public void outcome(String bundleId, PluginOperation operation, PluginOperationOutcome outcome) { }
}
