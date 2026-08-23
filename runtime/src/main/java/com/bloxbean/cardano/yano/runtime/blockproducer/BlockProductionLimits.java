package com.bloxbean.cardano.yano.runtime.blockproducer;

import java.math.BigInteger;

/** Epoch-effective resource limits for one produced block. */
public record BlockProductionLimits(
        long maxBodyBytes,
        BigInteger maxExecutionMemory,
        BigInteger maxExecutionSteps) {

    public BlockProductionLimits {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        if (maxExecutionMemory != null && maxExecutionMemory.signum() <= 0) {
            throw new IllegalArgumentException("maxExecutionMemory must be positive when set");
        }
        if (maxExecutionSteps != null && maxExecutionSteps.signum() <= 0) {
            throw new IllegalArgumentException("maxExecutionSteps must be positive when set");
        }
    }

    public static BlockProductionLimits unbounded() {
        return new BlockProductionLimits(Long.MAX_VALUE, null, null);
    }
}
