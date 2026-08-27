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

    /** All unacknowledged jobs, including multiple parts for one epoch. */
    default List<EpochArchiveJob> pending(int limit) { return pendingAfter(-1, limit); }

    Optional<EpochArchiveJob> find(UUID jobId);

    ArchiveSourceLease acquire(EpochArchiveJob job, Instant expiresAt);

    EpochSourcePage<T> read(EpochArchiveJob job, Optional<String> cursor, int limit,
                            ArchiveSourceLease lease);

    /** Called only after the backend commit receipt and progress are durable. */
    default void acknowledge(EpochArchiveJob job) { }
}
