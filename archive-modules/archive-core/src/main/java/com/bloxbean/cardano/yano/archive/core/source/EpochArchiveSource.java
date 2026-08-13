package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable, paged source contract; implementations must survive process restart. */
public interface EpochArchiveSource<T> {
    ArchiveDatasetId dataset();

    List<EpochArchiveJob> pendingAfter(long epochExclusive, int limit);

    Optional<EpochArchiveJob> find(UUID jobId);

    ArchiveSourceLease acquire(EpochArchiveJob job, Instant expiresAt);

    EpochSourcePage<T> read(EpochArchiveJob job, Optional<String> cursor, int limit,
                            ArchiveSourceLease lease);
}
