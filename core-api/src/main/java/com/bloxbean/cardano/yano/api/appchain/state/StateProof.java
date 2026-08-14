package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.Objects;

/** Profile-tagged native point proof against one finalized state snapshot. */
public record StateProof(
        StateSnapshot snapshot,
        byte[] canonicalKey,
        byte[] value,
        Presence presence,
        String proofEncodingId,
        byte[] nativeProof
) {
    public StateProof {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        canonicalKey = Objects.requireNonNull(canonicalKey, "canonicalKey").clone();
        if (canonicalKey.length == 0) {
            throw new IllegalArgumentException("state proof key must not be empty");
        }
        value = value != null ? value.clone() : null;
        presence = Objects.requireNonNull(presence, "presence");
        if (presence == Presence.ABSENT ? value != null : value == null) {
            throw new IllegalArgumentException("state proof presence/value differ");
        }
        proofEncodingId = Objects.requireNonNull(
                proofEncodingId, "proofEncodingId");
        if (!snapshot.identity().profile().proofEncodingId().equals(proofEncodingId)) {
            throw new IllegalArgumentException("native proof encoding differs from profile");
        }
        nativeProof = Objects.requireNonNull(nativeProof, "nativeProof").clone();
        if (nativeProof.length == 0) {
            throw new IllegalArgumentException("native proof must not be empty");
        }
    }

    @Override public byte[] canonicalKey() { return canonicalKey.clone(); }
    @Override public byte[] value() { return value != null ? value.clone() : null; }
    @Override public byte[] nativeProof() { return nativeProof.clone(); }

    public enum Presence {
        PRESENT,
        ABSENT,
        /** Native inclusion of the profile's reserved logical-deletion value. */
        TOMBSTONED
    }
}
