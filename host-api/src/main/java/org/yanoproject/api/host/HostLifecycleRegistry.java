package org.yanoproject.api.host;

import org.yanoproject.api.plugin.operations.PluginOperationsRecorder;
import org.yanoproject.api.plugin.spi.PluginProducts;
import org.yanoproject.runtime.kernel.Subsystem;

import java.util.function.BooleanSupplier;

/**
 * Assembly-scoped registration into the host's existing pre-publication kernel stage.
 * Services start after host storage/sync setup and before read-model/API publication,
 * and stop before host storage and ordinary plugins. Registrations survive restart.
 *
 * <p>The owner-id methods (Host API 1.2) accept only trusted host-class-path (embedded) bundle ids,
 * and only the id of the calling plugin: the host checks it against the caller's context.</p>
 */
public interface HostLifecycleRegistry {
    /**
     * Transfer permanent-close ownership to the host after successful registration.
     * Rejected registrations remain caller-owned. Do not open stores/listeners here;
     * the existing Subsystem start/stop/close contract owns those operations.
     * Names must be unique. Registration is allowed during plugin initialization and
     * host assembly; registration after the host seals assembly is rejected.
     */
    void registerBeforePublication(Subsystem service);

    /**
     * Registers the owner's assembly step. Allowed only during the owner's {@code NodePlugin.init}, once
     * per owner. The host runs the steps once, in registration order, after every plugin initialized and
     * before it opens ledger state or seals N2N and lifecycle registration, each under the owner's plugin
     * class loader as TCCL. A failure aborts startup naming the owner.
     *
     * @param ownerBundleId embedded bundle id of the calling plugin
     * @param assembly assembly step
     */
    default void registerAssembly(String ownerBundleId, Runnable assembly) {
        throw new UnsupportedOperationException("Host does not support assembly registration");
    }

    /**
     * Registers a restart-readiness check consulted by the kernel's restart preflight: {@code false} keeps
     * the kernel stopped and is retryable, an exception fails it, an interrupt keeps it stopped. Allowed
     * during the owner's {@code init} or its assembly step, once per owner.
     *
     * @param ownerBundleId embedded bundle id of the calling plugin
     * @param ready readiness check
     */
    default void registerRestartReadiness(String ownerBundleId, BooleanSupplier ready) {
        throw new UnsupportedOperationException("Host does not support restart readiness registration");
    }

    /**
     * Registers the owner's request-facing products at the host's product positions (see
     * {@link PluginProducts}). Allowed during the owner's {@code init} or its assembly step, once per owner.
     * Close ownership stays with the caller.
     *
     * @param ownerBundleId embedded bundle id of the calling plugin
     * @param products product positions
     * @return this host instance's operations recorder
     */
    default PluginOperationsRecorder registerPluginProducts(String ownerBundleId, PluginProducts products) {
        throw new UnsupportedOperationException("Host does not support plugin product registration");
    }
}
