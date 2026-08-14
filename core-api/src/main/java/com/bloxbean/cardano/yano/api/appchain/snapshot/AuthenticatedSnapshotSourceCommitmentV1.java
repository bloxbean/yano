package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.List;

/** Deterministic incremental verifier for a series' declared source dataset commitment. */
public interface AuthenticatedSnapshotSourceCommitmentV1 {
    String seriesId();
    String algorithm();
    String wireVersion();

    /**
     * Deterministic semantic contract ID used when compatible composite generations overlap.
     * Implementations sharing an ID promise byte-identical initial/append/finish behavior.
     */
    default String compatibilityId() { return algorithm() + "@" + wireVersion(); }

    /** Initial fixed-size accumulator for a new canonical draft. */
    byte[] initial(SnapshotDescriptorDraftV1 draft);

    /** Advance the accumulator with exactly one canonical snapshot chunk. */
    byte[] append(byte[] accumulator, long chunkIndex, List<SnapshotEntry> entries);

    /** Produce the source root that must equal the draft's declared source dataset root. */
    byte[] finish(byte[] accumulator, long chunks, long entries);

    default AuthenticatedSnapshotSourceCommitmentV1 withSeriesId(String scopedSeriesId) {
        AuthenticatedSnapshotSourceCommitmentV1 delegate = this;
        return new AuthenticatedSnapshotSourceCommitmentV1() {
            @Override public String seriesId() { return scopedSeriesId; }
            @Override public String algorithm() { return delegate.algorithm(); }
            @Override public String wireVersion() { return delegate.wireVersion(); }
            @Override public String compatibilityId() { return delegate.compatibilityId(); }
            @Override public byte[] initial(SnapshotDescriptorDraftV1 draft) {
                return delegate.initial(draft);
            }
            @Override public byte[] append(byte[] accumulator, long chunkIndex,
                                           List<SnapshotEntry> entries) {
                return delegate.append(accumulator, chunkIndex, entries);
            }
            @Override public byte[] finish(byte[] accumulator, long chunks, long entries) {
                return delegate.finish(accumulator, chunks, entries);
            }
        };
    }
}
