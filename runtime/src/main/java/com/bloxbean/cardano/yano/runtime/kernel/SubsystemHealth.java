package com.bloxbean.cardano.yano.runtime.kernel;

import java.util.Map;

/**
 * Framework-neutral health model for runtime subsystems.
 */
public record SubsystemHealth(String name, Status status, String message, Map<String, Object> details) {
    /**
     * Coarse subsystem health state used by runtime health aggregation.
     */
    public enum Status {
        UP,
        DEGRADED,
        DOWN
    }

    public SubsystemHealth {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static SubsystemHealth up(String name) {
        return new SubsystemHealth(name, Status.UP, null, Map.of());
    }

    public static SubsystemHealth degraded(String name, String message) {
        return new SubsystemHealth(name, Status.DEGRADED, message, Map.of());
    }

    public static SubsystemHealth down(String name, String message) {
        return new SubsystemHealth(name, Status.DOWN, message, Map.of());
    }

    public boolean healthy() {
        return status == Status.UP;
    }
}
