package com.bloxbean.cardano.yano.api.appchain.snapshot;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable canonical input fixed by a snapshot begin intent. */
public record SnapshotDescriptorDraftV1(
        AuthenticatedSnapshotSeriesDescriptorV1 series,
        long sequence,
        String snapshotId,
        SnapshotSourceBoundary sourceBoundary,
        long baseAppChainHeight,
        long coveredFromHeight,
        long coveredThroughHeight,
        byte[] sourceDatasetRoot,
        long expectedChunks,
        long expectedEntries
) {
    private static final byte[] DOMAIN =
            "yano-authenticated-snapshot-draft-v1\0".getBytes(StandardCharsets.US_ASCII);

    public SnapshotDescriptorDraftV1 {
        series = Objects.requireNonNull(series, "series");
        if (sequence < 0 || snapshotId == null || snapshotId.isEmpty()
                || baseAppChainHeight < 0 || coveredFromHeight < 0
                || coveredThroughHeight < coveredFromHeight || expectedChunks < 0
                || expectedEntries < 0 || expectedEntries > series.maxEntriesPerSnapshot()) {
            throw new IllegalArgumentException("invalid authenticated snapshot draft");
        }
        if (snapshotId.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("snapshotId must not exceed 256 UTF-8 bytes");
        }
        sourceBoundary = Objects.requireNonNull(sourceBoundary, "sourceBoundary");
        sourceDatasetRoot = Objects.requireNonNull(sourceDatasetRoot, "sourceDatasetRoot").clone();
        if (sourceDatasetRoot.length != 32) {
            throw new IllegalArgumentException("sourceDatasetRoot must be 32 bytes");
        }
    }

    @Override public byte[] sourceDatasetRoot() { return sourceDatasetRoot.clone(); }

    public byte[] digest() {
        byte[] canonical = SnapshotCanonicalCodec.encodeDraft(this);
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(DOMAIN.length + canonical.length)
                .put(DOMAIN).put(canonical).array());
    }

    public SnapshotBuildTokenV1 token() {
        return new SnapshotBuildTokenV1(sequence, digest());
    }
}
