package com.bloxbean.cardano.yano.runtime.devnet.spi;

import java.util.function.Supplier;

/**
 * Narrow maintenance facade used by optional devnet tooling.
 */
public interface RuntimeMaintenance {
    /**
     * Runs a mutation while holding the runtime maintenance write lease.
     * Successful calls clear degraded state only for the same reason.
     *
     * @param reason operation reason used for maintenance/degraded tracking
     * @param mutation mutation to run
     * @param <T> result type
     * @return mutation result
     */
    <T> T runExclusive(String reason, MaintenanceMutation<T> mutation);

    /**
     * Runs a read while holding the runtime maintenance read lease.
     *
     * @param reason read operation name
     * @param read read operation
     * @param <T> result type
     * @return read result
     */
    <T> T runRead(String reason, Supplier<T> read);

    /**
     * Marks the runtime degraded for an operation.
     *
     * @param operation operation name
     * @param cause failure cause
     */
    void markDegraded(String operation, Throwable cause);

    /**
     * Marks the runtime degraded for an operation with a specific message.
     *
     * @param operation operation name
     * @param message degraded reason
     * @param cause failure cause
     */
    void markDegraded(String operation, String message, Throwable cause);
}
