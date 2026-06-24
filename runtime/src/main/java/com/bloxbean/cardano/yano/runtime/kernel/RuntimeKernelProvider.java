package com.bloxbean.cardano.yano.runtime.kernel;

/**
 * Implemented by runtime implementations that own their kernel directly.
 */
public interface RuntimeKernelProvider {
    NodeKernel kernel();
}
