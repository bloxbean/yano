package com.bloxbean.cardano.yano.api.appchain.effects;

/**
 * Thrown by {@link AppEffectEmitter#emit} when a deterministic emission cap
 * ({@code effects.max-per-block}, {@code effects.max-payload-bytes}) is
 * exceeded. Deterministic by construction — every node fails the same block
 * identically, so this is a diagnosable machine bug, never a divergence.
 */
public class EffectLimitExceededException extends RuntimeException {

    public EffectLimitExceededException(String message) {
        super(message);
    }
}
