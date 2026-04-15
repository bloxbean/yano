package com.bloxbean.cardano.yano.api.era;

/**
 * Provides era metadata queries for ledger-state consumers.
 * Implemented by {@code EraService} in node-runtime.
 */
public interface EraProvider {

    /**
     * Check if the given epoch is in the Conway era or later.
     *
     * @param epoch the epoch to check
     * @return true if Conway is active at this epoch
     */
    boolean isConwayOrLater(int epoch);

    /**
     * Get the first Conway era epoch, or null if Conway has not been reached.
     */
    Integer resolveFirstConwayEpochOrNull();
}
