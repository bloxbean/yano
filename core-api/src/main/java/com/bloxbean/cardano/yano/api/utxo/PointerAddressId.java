package com.bloxbean.cardano.yano.api.utxo;

/** Ledger pointer carried by a Shelley pointer address. */
public record PointerAddressId(long slot, int transactionIndex, int certificateIndex) {
    public PointerAddressId {
        if (slot < 0 || transactionIndex < 0 || certificateIndex < 0) {
            throw new IllegalArgumentException("Pointer coordinates must be non-negative");
        }
    }
}
