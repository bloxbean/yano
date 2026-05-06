package com.bloxbean.cardano.yano.api.era;

/**
 * Provides era metadata queries for ledger-state consumers.
 * Implemented by {@code EraService} in runtime.
 */
public interface EraProvider {

    /**
     * Check if the given epoch is in the requested era or a later era.
     *
     * @param epoch the epoch to check
     * @param eraValue numeric era value from {@code com.bloxbean.cardano.yaci.core.model.Era}
     * @return true if the era is active at this epoch or has already passed
     */
    default boolean isEraOrLater(int epoch, int eraValue) {
        Integer first = resolveFirstEpochOrNull(eraValue);
        return first != null && epoch >= first;
    }

    /**
     * Get the first epoch for a given era value, or null if that era has not been reached.
     *
     * @param eraValue numeric era value from {@code com.bloxbean.cardano.yaci.core.model.Era}
     */
    default Integer resolveFirstEpochOrNull(int eraValue) {
        return null;
    }

    /**
     * Get the first epoch for a given era value only when an exact era-start
     * boundary is known. Implementations must not infer epoch 0 from a later
     * earliest-known era here.
     *
     * @param eraValue numeric era value from {@code com.bloxbean.cardano.yaci.core.model.Era}
     */
    default Integer resolveKnownFirstEpochOrNull(int eraValue) {
        return null;
    }

    /**
     * Check if the given epoch is in the Conway era or later.
     *
     * @param epoch the epoch to check
     * @return true if Conway is active at this epoch
     */
    default boolean isConwayOrLater(int epoch) {
        Integer first = resolveFirstConwayEpochOrNull();
        return first != null && epoch >= first;
    }

    /**
     * Get the first Conway era epoch, or null if Conway has not been reached.
     */
    default Integer resolveFirstConwayEpochOrNull() {
        return resolveFirstEpochOrNull(7);
    }
}
