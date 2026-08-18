package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveCapabilities;
import com.bloxbean.cardano.yano.archive.api.ArchiveCommitBoundary;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveHealth;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveMaintenanceBudget;
import com.bloxbean.cardano.yano.archive.api.ArchiveRange;
import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRetentionCutoff;
import com.bloxbean.cardano.yano.archive.api.ArchiveWriteSession;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CycleScopedCoverageCacheTest {

    @Test
    void repeatedReadsInOneCycleHitTheBackendOnce() {
        CountingBackend backend = new CountingBackend();
        var cache = new CycleScopedCoverageCache(backend);

        cache.coverage(ArchiveDatasetId.TRANSACTION);
        cache.coverage(ArchiveDatasetId.TRANSACTION);
        cache.coverage(ArchiveDatasetId.TRANSACTION);

        assertThat(backend.reads(ArchiveDatasetId.TRANSACTION)).isEqualTo(1);
    }

    @Test
    void aCommitInOneDatasetDoesNotMakeAnotherDatasetsCoverageStale() {
        CountingBackend backend = new CountingBackend();
        var cache = new CycleScopedCoverageCache(backend);
        cache.coverage(ArchiveDatasetId.TRANSACTION);
        cache.coverage(ArchiveDatasetId.ACCOUNT_EVENT);

        cache.invalidate(ArchiveDatasetId.TRANSACTION);

        cache.coverage(ArchiveDatasetId.TRANSACTION);
        cache.coverage(ArchiveDatasetId.ACCOUNT_EVENT);
        assertThat(backend.reads(ArchiveDatasetId.TRANSACTION)).isEqualTo(2);
        assertThat(backend.reads(ArchiveDatasetId.ACCOUNT_EVENT)).isEqualTo(1);
    }

    @Test
    void everyCycleBoundaryReReadsDurableCoverage() {
        CountingBackend backend = new CountingBackend();
        var cache = new CycleScopedCoverageCache(backend);
        cache.coverage(ArchiveDatasetId.TRANSACTION);
        assertThat(cache.cachedDatasets()).isEqualTo(1);

        cache.beginCycle();

        assertThat(cache.cachedDatasets()).isZero();
        cache.coverage(ArchiveDatasetId.TRANSACTION);
        assertThat(backend.reads(ArchiveDatasetId.TRANSACTION)).isEqualTo(2);
    }

    @Test
    void aFreshCacheIsRestartSafeBecauseNothingSurvivesTheProcess() {
        CountingBackend backend = new CountingBackend();
        new CycleScopedCoverageCache(backend).coverage(ArchiveDatasetId.TRANSACTION);
        // A restart constructs a new cache, so the durable read that recognizes a
        // committed receipt with an absent cursor always happens again.
        new CycleScopedCoverageCache(backend).coverage(ArchiveDatasetId.TRANSACTION);
        assertThat(backend.reads(ArchiveDatasetId.TRANSACTION)).isEqualTo(2);
    }

    @Test
    void theDirectViewNeverCaches() {
        CountingBackend backend = new CountingBackend();
        ArchiveCoverageView view = ArchiveCoverageView.direct(backend);
        view.coverage(ArchiveDatasetId.TRANSACTION);
        view.invalidate(ArchiveDatasetId.TRANSACTION);
        view.coverage(ArchiveDatasetId.TRANSACTION);
        assertThat(backend.reads(ArchiveDatasetId.TRANSACTION)).isEqualTo(2);
    }

    private static final class CountingBackend implements ArchiveBackend {
        private final Map<ArchiveDatasetId, Integer> reads = new EnumMap<>(ArchiveDatasetId.class);

        int reads(ArchiveDatasetId dataset) {
            return reads.getOrDefault(dataset, 0);
        }

        @Override public ArchiveCoverage coverage(ArchiveDatasetId dataset) {
            reads.merge(dataset, 1, Integer::sum);
            return new ArchiveCoverage(dataset, 1, 1, List.of(new BlockRange(0, 10)));
        }

        @Override public ArchiveIdentity identity() { throw new UnsupportedOperationException(); }
        @Override public ArchiveCapabilities capabilities() { throw new UnsupportedOperationException(); }
        @Override public ArchiveWriteSession begin(ArchiveJob job) { throw new UnsupportedOperationException(); }
        @Override public Optional<ArchiveReceipt> findReceipt(UUID jobId) { throw new UnsupportedOperationException(); }
        @Override public ArchiveCoverage coverage(ArchiveReadSession session, ArchiveDatasetId dataset) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ArchiveCommitBoundary> latestBlockBoundary(
                ArchiveReadSession session, ArchiveDatasetId dataset, BlockRange range, OptionalLong atOrBeforeSlot) {
            throw new UnsupportedOperationException();
        }
        @Override public ArchiveReadSession openReadSession() { throw new UnsupportedOperationException(); }
        @Override public void invalidate(ArchiveDatasetId dataset, ArchiveRange range) {
            throw new UnsupportedOperationException();
        }
        @Override public int invalidateEpochJobsAfterSlot(ArchiveDatasetId dataset, long rollbackSlot) {
            throw new UnsupportedOperationException();
        }
        @Override public void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff) {
            throw new UnsupportedOperationException();
        }
        @Override public void maintain(ArchiveMaintenanceBudget budget) { throw new UnsupportedOperationException(); }
        @Override public ArchiveHealth health() { return ArchiveHealth.healthy(); }
        @Override public void close() { }
    }
}
