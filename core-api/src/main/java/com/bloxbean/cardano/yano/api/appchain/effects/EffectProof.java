package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.List;
import java.util.Objects;

/**
 * Composed proof that an immutable effect record was emitted in a finalized
 * app block (ADR app-layer/010 F4).
 *
 * <p>The record hashes through {@link #merklePath()} and the authenticated
 * {@link #effectCount()} into the count-bound {@link #effectsRoot()}.
 * The MPF proof then proves {@code ~fx/root/<height> -> effectsRoot} against
 * the historical {@link #stateRoot()} recorded by that block. Authenticating
 * that state root with a threshold certificate or an L1 anchor is a separate
 * finality step and can be performed against an independently trusted root.</p>
 */
public record EffectProof(int version,
                          EffectRecord record,
                          int effectCount,
                          List<EffectProofStep> merklePath,
                          byte[] effectsRoot,
                          byte[] stateRoot,
                          byte[] stateProofWire) {

    public static final int PROOF_VERSION = 1;

    public EffectProof {
        if (version != PROOF_VERSION) {
            throw new IllegalArgumentException("Unsupported effect proof version: " + version);
        }
        Objects.requireNonNull(record, "record");
        if (effectCount <= 0 || record.ordinal() < 0 || record.ordinal() >= effectCount) {
            throw new IllegalArgumentException("Invalid effect count/ordinal");
        }
        merklePath = List.copyOf(Objects.requireNonNull(merklePath, "merklePath"));
        if (effectsRoot == null || effectsRoot.length != 32) {
            throw new IllegalArgumentException("effectsRoot must be 32 bytes");
        }
        if (stateRoot == null || stateRoot.length != 32) {
            throw new IllegalArgumentException("stateRoot must be 32 bytes");
        }
        if (stateProofWire == null || stateProofWire.length == 0) {
            throw new IllegalArgumentException("stateProofWire is required");
        }
    }
}
