package com.bloxbean.cardano.yano.archive.api;

import java.util.Optional;
import java.util.OptionalLong;
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

    /** Coverage read from the same immutable generation as the supplied session. */
    ArchiveCoverage coverage(ArchiveReadSession session, ArchiveDatasetId dataset);

    /**
     * Latest valid committed block boundary inside {@code range}. The optional
     * slot cap supports an unambiguous at-or-before-slot read point.
     */
    Optional<ArchiveCommitBoundary> latestBlockBoundary(
            ArchiveReadSession session, ArchiveDatasetId dataset,
            BlockRange range, OptionalLong atOrBeforeSlot);

    ArchiveReadSession openReadSession();

    /** Bounded repositories backed by the same generation-pinned read session. */
    default ArchiveRepositorySet repositories() {
        throw new UnsupportedOperationException("archive backend does not expose repositories");
    }

    /** Optimized, locator-backed where necessary, and always verified against the pinned row store. */
    default Optional<ArchiveRecord> findTransaction(ArchiveReadSession session, byte[] txHash) {
        ArchiveQuery query = new ArchiveQuery(new BlockRange(0, Long.MAX_VALUE),
                java.util.Map.of("tx_hash", txHash), ArchivePageCursor.Order.ASC, 1, Optional.empty());
        return repositories().records(ArchiveDatasetId.TRANSACTION).query(session, query).rows().stream().findFirst();
    }

    void invalidate(ArchiveDatasetId dataset, ArchiveRange range);

    /**
     * Invalidates whole epoch jobs whose canonical boundary is later than the
     * supplied rollback slot. Epoch numbers alone are insufficient because a
     * boundary block can be rolled back while the target remains in that epoch.
     *
     * @return number of committed jobs removed
     */
    int invalidateEpochJobsAfterSlot(ArchiveDatasetId dataset, long rollbackSlot);

    void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff);

    void maintain(ArchiveMaintenanceBudget budget);

    ArchiveHealth health();

    @Override
    void close();
}
