package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.List;
import java.util.Objects;

/** Opaque predeclared handle used only to submit deterministic snapshot plans. */
public final class SnapshotSeriesHandle {
    private final AuthenticatedSnapshotSeriesDescriptorV1 descriptor;
    private final AuthenticatedSnapshotPlanCollector collector;

    public SnapshotSeriesHandle(AuthenticatedSnapshotSeriesDescriptorV1 descriptor,
                                AuthenticatedSnapshotPlanCollector collector) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    public String seriesId() { return descriptor.seriesId(); }
    public AuthenticatedSnapshotSeriesDescriptorV1 descriptor() { return descriptor; }

    public SnapshotBuildTokenV1 begin(long sequence, String snapshotId, SnapshotSourceBoundary boundary,
                      long baseAppChainHeight, long coveredFromHeight, long coveredThroughHeight,
                      byte[] sourceDatasetRoot, long expectedChunks, long expectedEntries) {
        return collector.begin(descriptor, sequence, snapshotId, boundary, baseAppChainHeight,
                coveredFromHeight, coveredThroughHeight, sourceDatasetRoot,
                expectedChunks, expectedEntries);
    }

    public SnapshotBuildTokenV1 draftToken(long sequence, String snapshotId,
                                           SnapshotSourceBoundary boundary,
                                           long baseAppChainHeight, long coveredFromHeight,
                                           long coveredThroughHeight, byte[] sourceDatasetRoot,
                                           long expectedChunks, long expectedEntries) {
        return new SnapshotDescriptorDraftV1(descriptor, sequence, snapshotId, boundary,
                baseAppChainHeight, coveredFromHeight, coveredThroughHeight, sourceDatasetRoot,
                expectedChunks, expectedEntries).token();
    }

    public void appendChunk(SnapshotBuildTokenV1 token, long chunkIndex, List<SnapshotEntry> entries) {
        collector.appendChunk(descriptor, token, chunkIndex, entries);
    }

    public void seal(SnapshotBuildTokenV1 token) {
        collector.seal(descriptor, token);
    }
}
