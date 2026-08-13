package com.bloxbean.cardano.yano.archive.api;

import java.util.Optional;
import java.util.UUID;

/** Backend-neutral atomic archive storage contract. */
public interface ArchiveBackend extends AutoCloseable {
    ArchiveIdentity identity();

    ArchiveCapabilities capabilities();

    /**
     * Starts an atomic job. Repeating a committed deterministic job is
     * idempotent: commit must return the original receipt and must not append
     * duplicate rows.
     */
    ArchiveWriteSession begin(ArchiveJob job);

    Optional<ArchiveReceipt> findReceipt(UUID jobId);

    ArchiveCoverage coverage(ArchiveDatasetId dataset);

    ArchiveReadSession openReadSession();

    void invalidate(ArchiveDatasetId dataset, ArchiveRange range);

    void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff);

    void maintain(ArchiveMaintenanceBudget budget);

    ArchiveHealth health();

    @Override
    void close();
}
