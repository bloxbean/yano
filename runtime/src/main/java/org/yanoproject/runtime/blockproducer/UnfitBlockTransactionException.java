package org.yanoproject.runtime.blockproducer;

/**
 * A selected transaction cannot be placed in any block: it cannot fit an otherwise empty block under the
 * effective protocol limits, or it claims {@code isValid=false}, which the builder cannot encode (ADR-056 §6).
 * Retrying the same selection cannot progress, so producers invalidate the transaction and its dependents.
 */
public final class UnfitBlockTransactionException extends IllegalStateException {
    private final String transactionHash;

    public UnfitBlockTransactionException(String transactionHash) {
        super("Selected transaction " + transactionHash
                + " cannot fit an empty block under the effective protocol resource limits");
        this.transactionHash = transactionHash;
    }

    public UnfitBlockTransactionException(String transactionHash, String message) {
        super(message);
        this.transactionHash = transactionHash;
    }

    public String transactionHash() {
        return transactionHash;
    }
}
