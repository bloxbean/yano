package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.Objects;

/** Authenticated series head. */
public record SnapshotHeadV1(long sequence, byte[] descriptorCommitment) {
    public SnapshotHeadV1 {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
        descriptorCommitment = Objects.requireNonNull(descriptorCommitment, "descriptorCommitment").clone();
        if (descriptorCommitment.length != 32) {
            throw new IllegalArgumentException("descriptorCommitment must be 32 bytes");
        }
    }
    @Override public byte[] descriptorCommitment() { return descriptorCommitment.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof SnapshotHeadV1 that && sequence == that.sequence
                && java.util.Arrays.equals(descriptorCommitment, that.descriptorCommitment);
    }
    @Override public int hashCode() {
        return 31 * Long.hashCode(sequence) + java.util.Arrays.hashCode(descriptorCommitment);
    }
}
