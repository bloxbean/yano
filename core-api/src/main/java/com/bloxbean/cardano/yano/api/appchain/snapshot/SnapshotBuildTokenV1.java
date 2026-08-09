package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.Objects;

/** Consensus continuation token binding every chunk and seal to one immutable snapshot draft. */
public record SnapshotBuildTokenV1(long sequence, byte[] descriptorDraftDigest) {
    public SnapshotBuildTokenV1 {
        descriptorDraftDigest = Objects.requireNonNull(
                descriptorDraftDigest, "descriptorDraftDigest").clone();
        if (sequence < 0 || descriptorDraftDigest.length != 32) {
            throw new IllegalArgumentException("invalid authenticated snapshot build token");
        }
    }

    @Override public byte[] descriptorDraftDigest() { return descriptorDraftDigest.clone(); }
}
