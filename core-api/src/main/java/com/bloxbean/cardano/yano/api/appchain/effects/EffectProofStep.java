package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.Objects;

/** One level of an ordered effect-list Merkle proof. */
public record EffectProofStep(EffectProofSide side, byte[] siblingHash) {

    public EffectProofStep {
        Objects.requireNonNull(side, "side");
        siblingHash = siblingHash != null ? siblingHash : new byte[0];
        if (side == EffectProofSide.PASS_THROUGH && siblingHash.length != 0) {
            throw new IllegalArgumentException("PASS_THROUGH must not carry a sibling hash");
        }
        if (side != EffectProofSide.PASS_THROUGH && siblingHash.length != 32) {
            throw new IllegalArgumentException("Merkle sibling hash must be 32 bytes");
        }
    }
}
