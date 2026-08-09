package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.Objects;

/** Authenticated progress record for a bounded multi-block snapshot build. */
public record SnapshotBuildReceiptV1(
        long sequence,
        String snapshotId,
        SnapshotSourceBoundary sourceBoundary,
        long baseHeight,
        long coveredFromHeight,
        long coveredThroughHeight,
        AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage recoveryCoverage,
        byte[] descriptorDraftDigest,
        byte[] sourceDatasetRoot,
        String sourceCommitmentAlgorithm,
        String sourceCommitmentWireVersion,
        long expectedChunks,
        long nextChunk,
        long expectedEntries,
        long receivedEntries,
        byte[] lastApplicationKey,
        byte[] sourceAccumulator,
        byte[] partialRoot
) {
    public SnapshotBuildReceiptV1 {
        if (sequence < 0 || baseHeight < 0 || coveredFromHeight < 0
                || coveredThroughHeight < coveredFromHeight || expectedChunks < 0
                || nextChunk < 0 || nextChunk > expectedChunks || expectedEntries < 0
                || receivedEntries < 0 || receivedEntries > expectedEntries) {
            throw new IllegalArgumentException("invalid snapshot receipt counters");
        }
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        sourceBoundary = Objects.requireNonNull(sourceBoundary, "sourceBoundary");
        recoveryCoverage = Objects.requireNonNull(recoveryCoverage, "recoveryCoverage");
        descriptorDraftDigest = require32(descriptorDraftDigest, "descriptorDraftDigest");
        sourceDatasetRoot = require32(sourceDatasetRoot, "sourceDatasetRoot");
        sourceCommitmentAlgorithm = Objects.requireNonNull(sourceCommitmentAlgorithm, "sourceCommitmentAlgorithm");
        sourceCommitmentWireVersion = Objects.requireNonNull(sourceCommitmentWireVersion, "sourceCommitmentWireVersion");
        lastApplicationKey = Objects.requireNonNull(lastApplicationKey, "lastApplicationKey").clone();
        sourceAccumulator = require32(sourceAccumulator, "sourceAccumulator");
        partialRoot = require32(partialRoot, "partialRoot");
    }

    @Override public byte[] descriptorDraftDigest() { return descriptorDraftDigest.clone(); }
    @Override public byte[] sourceDatasetRoot() { return sourceDatasetRoot.clone(); }
    @Override public byte[] lastApplicationKey() { return lastApplicationKey.clone(); }
    @Override public byte[] sourceAccumulator() { return sourceAccumulator.clone(); }
    @Override public byte[] partialRoot() { return partialRoot.clone(); }

    private static byte[] require32(byte[] value, String name) {
        byte[] copy = Objects.requireNonNull(value, name).clone();
        if (copy.length != 32) throw new IllegalArgumentException(name + " must be 32 bytes");
        return copy;
    }
}
