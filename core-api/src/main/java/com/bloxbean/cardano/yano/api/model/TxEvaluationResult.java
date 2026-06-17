package com.bloxbean.cardano.yano.api.model;

/**
 * Computed execution units for a transaction redeemer.
 */
public record TxEvaluationResult(String tag, int index, long memory, long steps) {
}
