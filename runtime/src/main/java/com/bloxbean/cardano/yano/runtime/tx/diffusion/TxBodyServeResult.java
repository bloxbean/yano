package com.bloxbean.cardano.yano.runtime.tx.diffusion;

import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;

import java.util.List;

public record TxBodyServeResult(
        List<MemPoolTransaction> transactions,
        int missing,
        long servedBytes
) {
    public TxBodyServeResult {
        transactions = transactions != null ? List.copyOf(transactions) : List.of();
        missing = Math.max(0, missing);
        servedBytes = Math.max(0L, servedBytes);
    }

    public static TxBodyServeResult empty() {
        return new TxBodyServeResult(List.of(), 0, 0);
    }
}
