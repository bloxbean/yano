package com.bloxbean.cardano.yano.runtime.chain;

/** Immutable operational view of bounded mempool state. */
public record MempoolStats(
        int transactions,
        long transactionBytes,
        int utxoIndexEntries,
        int producedOutputs,
        int spentOutpoints,
        int dependencyEdges,
        long estimatedIndexBytes,
        long duplicateRejections,
        long conflictRejections,
        long capacityRejections,
        long malformedRejections,
        long ledgerRejections,
        long cascadedRemovals,
        int admissionQueueLength,
        long totalAdmissionWaitNanos,
        long totalAdmissionHoldNanos,
        long totalValidationNanos,
        long slowValidations) {
}
