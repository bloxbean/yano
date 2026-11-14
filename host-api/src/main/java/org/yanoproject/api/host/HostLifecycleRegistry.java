package org.yanoproject.api.host;

import org.yanoproject.runtime.kernel.Subsystem;

/**
 * Init-scoped registration into the host's existing pre-publication kernel stage.
 * Services start after host storage/sync setup and before read-model/API publication,
 * and stop before host storage and ordinary plugins. Registrations survive restart.
 */
public interface HostLifecycleRegistry {
    /**
     * Transfer permanent-close ownership to the host after successful registration.
     * Rejected registrations remain caller-owned. Do not open stores/listeners here;
     * the existing Subsystem start/stop/close contract owns those operations.
     * Names must be unique. Registration after initialization is rejected.
     */
    void registerBeforePublication(Subsystem service);
}
