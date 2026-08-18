package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dataset, cycle-scoped coverage cache.
 *
 * <p>Entries are keyed by dataset, so a commit in one dataset never makes
 * another dataset's coverage stale. The cache is cleared at every projection
 * cycle boundary and invalidated immediately after any mutation that can change
 * coverage: commit, invalidation (including canonical reconciliation),
 * retention, and reset.
 *
 * <p>It is in-memory only and therefore restart-safe by construction: a fresh
 * process always performs the durable read that recognizes a committed receipt
 * whose control cursor was not persisted.
 */
public final class CycleScopedCoverageCache implements ArchiveCoverageView {
    private final ArchiveBackend backend;
    private final Map<ArchiveDatasetId, ArchiveCoverage> cached = new ConcurrentHashMap<>();

    public CycleScopedCoverageCache(ArchiveBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public ArchiveCoverage coverage(ArchiveDatasetId dataset) {
        Objects.requireNonNull(dataset, "dataset");
        ArchiveCoverage hit = cached.get(dataset);
        if (hit != null) return hit;
        // Read outside the map: computeIfAbsent would hold a ConcurrentHashMap
        // bin lock across blocking backend I/O, serializing unrelated datasets
        // that happen to hash to the same bin.
        ArchiveCoverage read = backend.coverage(dataset);
        ArchiveCoverage raced = cached.putIfAbsent(dataset, read);
        return raced == null ? read : raced;
    }

    @Override
    public void invalidate(ArchiveDatasetId dataset) {
        Objects.requireNonNull(dataset, "dataset");
        cached.remove(dataset);
    }

    /** Clears every dataset entry at a projection cycle boundary. */
    public void beginCycle() {
        cached.clear();
    }

    /** Test/diagnostic accessor for the number of datasets currently cached. */
    public int cachedDatasets() {
        return cached.size();
    }
}
