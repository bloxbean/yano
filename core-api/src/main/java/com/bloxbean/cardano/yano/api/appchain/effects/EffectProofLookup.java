package com.bloxbean.cardano.yano.api.appchain.effects;

/**
 * Result of looking up a composed effect proof. A retained per-block metadata
 * row lets the runtime report an unknown ordinal separately from proof
 * material that passed the retention horizon. Only an AVAILABLE proof
 * cryptographically authenticates the count; PRUNED is a node-local
 * availability classification.
 */
public record EffectProofLookup(Status status, EffectProof proof, int effectCount) {

    public enum Status {
        AVAILABLE,
        NOT_FOUND,
        PRUNED
    }

    public EffectProofLookup {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if ((status == Status.AVAILABLE) != (proof != null)) {
            throw new IllegalArgumentException("proof must be present exactly for AVAILABLE");
        }
        if (effectCount < 0) {
            throw new IllegalArgumentException("effectCount must be >= 0");
        }
    }

    public static EffectProofLookup available(EffectProof proof) {
        return new EffectProofLookup(Status.AVAILABLE, proof, proof.effectCount());
    }

    public static EffectProofLookup notFound(int effectCount) {
        return new EffectProofLookup(Status.NOT_FOUND, null, Math.max(0, effectCount));
    }

    public static EffectProofLookup pruned(int effectCount) {
        return new EffectProofLookup(Status.PRUNED, null, effectCount);
    }
}
