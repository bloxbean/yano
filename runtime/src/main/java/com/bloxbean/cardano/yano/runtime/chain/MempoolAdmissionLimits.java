package com.bloxbean.cardano.yano.runtime.chain;

/** Hard admission limits. A non-positive byte or index limit is unbounded. */
public record MempoolAdmissionLimits(int maxTransactions, long maxBytes, int maxUtxoIndexEntries) {
    public static MempoolAdmissionLimits unbounded() {
        return new MempoolAdmissionLimits(Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
    }
}
