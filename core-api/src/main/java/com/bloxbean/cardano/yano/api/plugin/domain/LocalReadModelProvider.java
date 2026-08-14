package com.bloxbean.cardano.yano.api.plugin.domain;

/** Factory and lifecycle contract for one manifested node-local read model. */
public interface LocalReadModelProvider {
    /** Stable contribution identity from the owning bundle manifest. */
    String id();

    /** Starts all owned workers/registrations and returns their lifecycle fence. */
    AutoCloseable start(LocalReadModelContext context);
}
