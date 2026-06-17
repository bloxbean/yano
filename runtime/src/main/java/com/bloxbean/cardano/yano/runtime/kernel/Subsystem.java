package com.bloxbean.cardano.yano.runtime.kernel;

/**
 * Cohesive runtime unit with explicit lifecycle.
 */
public interface Subsystem extends AutoCloseable {
    String name();

    default void init(SubsystemContext ctx) {
    }

    void start();

    void stop();

    @Override
    default void close() {
    }

    default SubsystemHealth health() {
        return SubsystemHealth.up(name());
    }
}
