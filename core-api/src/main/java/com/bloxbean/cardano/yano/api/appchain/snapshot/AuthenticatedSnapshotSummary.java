package com.bloxbean.cardano.yano.api.appchain.snapshot;

/** Compact REST/client discovery view of one committed logical snapshot. */
public record AuthenticatedSnapshotSummary(
        String seriesId,
        long sequence,
        String snapshotId,
        long entryCount,
        long completedAppChainHeight,
        String profile,
        String lifecycle
) { }
