package com.bloxbean.cardano.yano.runtime.devnet;

/**
 * Reports devnet maintenance failures after an operation has started mutating
 * runtime state.
 */
@FunctionalInterface
public interface MaintenanceFailureReporter {
    void markDegraded(String operation, String message, Throwable cause);

    static MaintenanceFailureReporter noop() {
        return (operation, message, cause) -> {
        };
    }
}
