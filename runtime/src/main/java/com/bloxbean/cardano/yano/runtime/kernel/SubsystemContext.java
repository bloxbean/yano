package com.bloxbean.cardano.yano.runtime.kernel;

import com.bloxbean.cardano.yaci.events.api.EventBus;

import java.util.Map;
import java.util.Objects;

/**
 * Shared runtime context visible to subsystems during initialization.
 */
public record SubsystemContext(EventBus eventBus,
                               Schedulers schedulers,
                               Map<String, Object> config,
                               ServiceRegistry services) {
    public SubsystemContext {
        schedulers = Objects.requireNonNull(schedulers, "schedulers");
        config = config == null ? Map.of() : Map.copyOf(config);
        services = services == null ? new ServiceRegistry() : services;
    }
}
