package com.bloxbean.cardano.yano.api.appchain.effects;

/**
 * Authenticated effect-outcome commitment mode.
 *
 * <p>The enum names are configuration/status values. Wire/profile codecs must
 * use their own explicit numeric codes rather than {@link Enum#ordinal()}.</p>
 */
public enum EffectOutcomeCommitment {
    /** One authenticated outcome leaf per incorporated effect. */
    PER_EFFECT,
    /** One authenticated outcome-root leaf per effectful block. */
    PER_BLOCK
}
