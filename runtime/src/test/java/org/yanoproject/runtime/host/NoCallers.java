package org.yanoproject.runtime.host;

import java.util.Optional;

/** Test callers of a host without plugins: every owner registration is rejected. */
public final class NoCallers implements DefaultHostLifecycleRegistry.Callers {
    @Override public Optional<String> owner() { return Optional.empty(); }
    @Override public void runAsOwner(String bundleId, Runnable step) { step.run(); }
    @Override public boolean embedded(String bundleId) { return false; }
}
