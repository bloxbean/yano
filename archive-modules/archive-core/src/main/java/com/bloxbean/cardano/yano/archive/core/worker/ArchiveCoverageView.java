package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Objects;

/**
 * Read access to committed coverage, allowing a caller to reuse one durable read
 * within a bounded scope.
 *
 * <p>Every coverage read costs a bounded backend permit, and block workers read
 * coverage at the start of every batch. That read is required after a crash to
 * recognize a committed receipt whose control cursor was not persisted, but it
 * does not have to repeat once the answer is known unchanged.
 */
public interface ArchiveCoverageView {
    ArchiveCoverage coverage(ArchiveDatasetId dataset);

    /** Drops any cached answer for one dataset after its coverage may have changed. */
    void invalidate(ArchiveDatasetId dataset);

    /** Uncached passthrough; every call reads the backend. */
    static ArchiveCoverageView direct(ArchiveBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return new ArchiveCoverageView() {
            @Override public ArchiveCoverage coverage(ArchiveDatasetId dataset) {
                return backend.coverage(dataset);
            }

            @Override public void invalidate(ArchiveDatasetId dataset) { }
        };
    }
}
