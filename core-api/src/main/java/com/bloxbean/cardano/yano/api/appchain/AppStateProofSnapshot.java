package com.bloxbean.cardano.yano.api.appchain;

import java.util.Objects;

/**
 * One inclusion or exclusion proof bound to an exact committed app-chain
 * state snapshot.
 *
 * @param key             original state key proved by {@code proofWire}
 * @param value           committed value, or {@code null} for an exclusion proof
 * @param proofWire       MPF wire proof for {@code key} at {@code stateRoot}
 * @param stateRoot       committed state root against which the proof verifies
 * @param committedHeight finalized height whose post-state has {@code stateRoot}
 */
public record AppStateProofSnapshot(
        byte[] key,
        byte[] value,
        byte[] proofWire,
        byte[] stateRoot,
        long committedHeight
) {

    public AppStateProofSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(proofWire, "proofWire");
        Objects.requireNonNull(stateRoot, "stateRoot");
        if (proofWire.length == 0) {
            throw new IllegalArgumentException("proofWire must not be empty");
        }
        if (stateRoot.length != 32) {
            throw new IllegalArgumentException("stateRoot must contain exactly 32 bytes");
        }
        if (committedHeight < 0) {
            throw new IllegalArgumentException("committedHeight must not be negative");
        }
        key = key.clone();
        value = value != null ? value.clone() : null;
        proofWire = proofWire.clone();
        stateRoot = stateRoot.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public byte[] value() {
        return value != null ? value.clone() : null;
    }

    @Override
    public byte[] proofWire() {
        return proofWire.clone();
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
    }
}
