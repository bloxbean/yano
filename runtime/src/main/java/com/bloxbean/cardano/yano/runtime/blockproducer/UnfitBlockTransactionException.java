package com.bloxbean.cardano.yano.runtime.blockproducer;

/**
 * A selected transaction cannot fit an otherwise empty block under the
 * effective protocol limits, so retrying the same selection cannot progress.
 */
public final class UnfitBlockTransactionException extends IllegalStateException {
    private final String transactionHash;

    public UnfitBlockTransactionException(String transactionHash) {
        super("Selected transaction " + transactionHash
                + " cannot fit an empty block under the effective protocol resource limits");
        this.transactionHash = transactionHash;
    }

    public String transactionHash() {
        return transactionHash;
    }
}
