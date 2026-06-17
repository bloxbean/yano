package com.bloxbean.cardano.yano.runtime.kernel;

/**
 * Coarse lifecycle state for the runtime kernel.
 */
public enum KernelState {
    CREATED,
    INITIALIZED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
