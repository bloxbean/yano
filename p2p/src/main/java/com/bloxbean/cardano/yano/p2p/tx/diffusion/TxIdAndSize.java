package com.bloxbean.cardano.yano.p2p.tx.diffusion;

import java.util.Locale;
import java.util.Objects;

public record TxIdAndSize(String txHash, int size) {
    public TxIdAndSize {
        txHash = normalizeHash(txHash);
        size = Math.max(0, size);
    }

    static String normalizeHash(String txHash) {
        Objects.requireNonNull(txHash, "txHash");
        String normalized = txHash.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("txHash must not be blank");
        }
        return normalized;
    }
}
