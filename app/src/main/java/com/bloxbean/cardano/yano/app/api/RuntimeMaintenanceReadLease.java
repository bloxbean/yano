package com.bloxbean.cardano.yano.app.api;

import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;

import java.util.Objects;

/**
 * Request-scoped owner for the runtime maintenance read lease.
 *
 * <p>The normal response filter closes the lease promptly. The request-context
 * destruction callback is the mandatory fallback for failures that bypass
 * response filtering, preventing one failed REST request from blocking node
 * shutdown or devnet maintenance indefinitely.
 */
@RequestScoped
public class RuntimeMaintenanceReadLease implements AutoCloseable {
    private RuntimeMaintenanceGate.ReadLease lease;

    synchronized void open(RuntimeMaintenanceGate gate, String operation) {
        Objects.requireNonNull(gate, "gate");
        if (lease != null) throw new IllegalStateException("runtime maintenance read lease already open");
        lease = gate.enterRead(operation);
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        RuntimeMaintenanceGate.ReadLease selected = lease;
        lease = null;
        if (selected != null) selected.close();
    }
}
