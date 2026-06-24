package com.bloxbean.cardano.yano.runtime.kernel;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal typed registry for rare cross-subsystem lookups that cannot be passed
 * through constructors during assembly.
 */
public final class ServiceRegistry {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, T service) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (service == null) {
            services.remove(type);
            return;
        }
        services.put(type, type.cast(service));
    }

    public <T> Optional<T> get(Class<T> type) {
        Object service = services.get(type);
        return service == null ? Optional.empty() : Optional.of(type.cast(service));
    }

    public <T> T require(Class<T> type) {
        return get(type).orElseThrow(() -> new IllegalStateException("Required service not registered: " + type.getName()));
    }
}
