package org.yanoproject.archive.core.dataset;

public record TransactionFact(byte[] txHash, int txIndex, boolean valid, Long fee) {
    public TransactionFact(byte[] txHash, int txIndex, boolean valid, long fee) {
        this(txHash, txIndex, valid, Long.valueOf(fee));
    }
}
