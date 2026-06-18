package com.bloxbean.cardano.yano.runtime.devnet.spi;

/**
 * Mutation callback executed while the runtime maintenance write lease is held.
 *
 * @param <T> result type
 */
@FunctionalInterface
public interface MaintenanceMutation<T> {
    /**
     * Runs the mutation.
     *
     * @return mutation result
     */
    T run();
}
