package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Objects;

/** Committed one-to-one mapping from a stable accepted L1 outpoint to its mirror. */
public record EutxoDepositRecord(
        EutxoDepositClaim claim,
        EutxoOutpoint mirroredOutpoint,
        long creditedHeight
) {
    public EutxoDepositRecord {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(mirroredOutpoint, "mirroredOutpoint");
        if (!mirroredOutpoint.equals(claim.mirroredOutpoint())) {
            throw new IllegalArgumentException("mirrored outpoint does not match the deposit claim");
        }
        if (creditedHeight < 0) {
            throw new IllegalArgumentException("credited height cannot be negative");
        }
    }

    public byte[] encode() {
        return EutxoCbor.encodeDepositRecord(this);
    }

    public static EutxoDepositRecord decode(byte[] bytes) {
        return EutxoCbor.decodeDepositRecord(bytes);
    }
}
