package org.yanoproject.api.model;

/**
 * Computed execution units for a transaction redeemer.
 */
public record TxEvaluationResult(String tag, int index, long memory, long steps) {
}
