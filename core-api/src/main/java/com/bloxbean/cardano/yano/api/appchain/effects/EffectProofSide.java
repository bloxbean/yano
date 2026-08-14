package com.bloxbean.cardano.yano.api.appchain.effects;

/**
 * Position of one sibling in an ordered {@code effectsRoot} Merkle proof.
 * {@link #PASS_THROUGH} represents the unpaired final node at an odd-width
 * level; its proof step carries no hash.
 */
public enum EffectProofSide {
    LEFT,
    RIGHT,
    PASS_THROUGH
}
