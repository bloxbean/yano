package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Arrays;
import java.util.Objects;

/** Epoch-pinned protocol parameters in the canonical ADR-028 CBOR profile. */
public record ProtocolParamsView(long effectiveEpoch, byte[] canonicalCbor) {
    public ProtocolParamsView {
        if (effectiveEpoch < 0) {
            throw new IllegalArgumentException("effectiveEpoch must not be negative");
        }
        Objects.requireNonNull(canonicalCbor, "canonicalCbor");
        canonicalCbor = canonicalCbor.clone();
    }

    @Override
    public byte[] canonicalCbor() {
        return canonicalCbor.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProtocolParamsView that
                && effectiveEpoch == that.effectiveEpoch
                && Arrays.equals(canonicalCbor, that.canonicalCbor);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(effectiveEpoch) + Arrays.hashCode(canonicalCbor);
    }
}
