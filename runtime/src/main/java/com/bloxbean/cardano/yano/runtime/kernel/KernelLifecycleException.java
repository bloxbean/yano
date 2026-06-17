package com.bloxbean.cardano.yano.runtime.kernel;

/**
 * Aggregates lifecycle failures while preserving suppressed exceptions.
 */
public final class KernelLifecycleException extends RuntimeException {
    public KernelLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }

    public KernelLifecycleException(String message) {
        super(message);
    }
}
