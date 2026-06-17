package com.bloxbean.cardano.yano.runtime.config;

/**
 * Genesis-derived values required to convert rollback retention epochs to slot
 * and block windows.
 */
public record RollbackRetentionGenesisValues(long epochLength, double activeSlotsCoeff) {
}
