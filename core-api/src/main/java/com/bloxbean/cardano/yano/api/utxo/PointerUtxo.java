package com.bloxbean.cardano.yano.api.utxo;

import java.math.BigInteger;
import java.util.Objects;

/** Minimal unspent pointer-address output needed by pre-Conway stake snapshots. */
public record PointerUtxo(long creationSlot, BigInteger lovelace, PointerAddressId pointer) {
    public PointerUtxo {
        if (creationSlot < 0) {
            throw new IllegalArgumentException("Pointer UTXO creation slot must be non-negative");
        }
        Objects.requireNonNull(lovelace, "lovelace");
        if (lovelace.signum() < 0) {
            throw new IllegalArgumentException("Pointer UTXO lovelace must be non-negative");
        }
    }

    /** False when the address is pointer-typed but its payload cannot be decoded safely. */
    public boolean resolvable() {
        return pointer != null;
    }
}
