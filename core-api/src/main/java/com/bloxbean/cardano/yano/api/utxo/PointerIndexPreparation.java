package com.bloxbean.cardano.yano.api.utxo;

/** Result of preparing the optional pointer UTXO index for a boundary read. */
public record PointerIndexPreparation(boolean ready, boolean backfilled) {
    public static PointerIndexPreparation unavailable() {
        return new PointerIndexPreparation(false, false);
    }

    public static PointerIndexPreparation ready(boolean backfilled) {
        return new PointerIndexPreparation(true, backfilled);
    }
}
