package com.bloxbean.cardano.yano.archive.core.consistency;

import com.bloxbean.cardano.yano.archive.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveConsistencyPlannerTest {
    @Test
    void selectsTheEndOfTheCommonContiguousRange() {
        FixtureBackend backend = new FixtureBackend();
        backend.coverage.put(ArchiveDatasetId.TRANSACTION, List.of(new BlockRange(0, 9), new BlockRange(20, 30)));
        backend.coverage.put(ArchiveDatasetId.ACCOUNT_EVENT, List.of(new BlockRange(0, 7), new BlockRange(20, 25)));
        backend.boundaries.put(ArchiveDatasetId.TRANSACTION, List.of(boundary(ArchiveDatasetId.TRANSACTION, 0, 9),
                boundary(ArchiveDatasetId.TRANSACTION, 20, 30)));
        backend.boundaries.put(ArchiveDatasetId.ACCOUNT_EVENT, List.of(boundary(ArchiveDatasetId.ACCOUNT_EVENT, 0, 7),
                boundary(ArchiveDatasetId.ACCOUNT_EVENT, 20, 25)));

        try (ArchiveReadSession read = backend.openReadSession()) {
            ArchiveConsistencyPoint first = ArchiveConsistencyPlanner.plan(backend, read,
                    Set.of(ArchiveDatasetId.TRANSACTION, ArchiveDatasetId.ACCOUNT_EVENT),
                    0, OptionalLong.empty(), OptionalLong.empty());
            assertThat(first.completeRange()).isEqualTo(new BlockRange(0, 7));
            assertThat(first.asOf().slot()).isEqualTo(70);

            ArchiveConsistencyPoint second = ArchiveConsistencyPlanner.plan(backend, read,
                    Set.of(ArchiveDatasetId.TRANSACTION, ArchiveDatasetId.ACCOUNT_EVENT),
                    20, OptionalLong.empty(), OptionalLong.empty());
            assertThat(second.completeRange()).isEqualTo(new BlockRange(20, 25));
        }
    }

    @Test
    void rejectsARequestedStartInsideACommonGap() {
        FixtureBackend backend = new FixtureBackend();
        backend.coverage.put(ArchiveDatasetId.TRANSACTION, List.of(new BlockRange(0, 9), new BlockRange(20, 30)));
        backend.coverage.put(ArchiveDatasetId.ACCOUNT_EVENT, List.of(new BlockRange(0, 7), new BlockRange(20, 25)));

        try (ArchiveReadSession read = backend.openReadSession()) {
            assertThatThrownBy(() -> ArchiveConsistencyPlanner.plan(backend, read,
                    Set.of(ArchiveDatasetId.TRANSACTION, ArchiveDatasetId.ACCOUNT_EVENT),
                    10, OptionalLong.empty(), OptionalLong.empty()))
                    .isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("no common finalized coverage");
        }
    }

    private static ArchiveCommitBoundary boundary(ArchiveDatasetId dataset, long from, long to) {
        byte[] hash = new byte[] {(byte) to};
        return new ArchiveCommitBoundary(dataset, 1, new BlockRange(from, to),
                new ArchiveRangeAnchor(from * 10, hash, to * 10, hash), 5);
    }

    private static final class FixtureBackend implements ArchiveBackend {
        final Map<ArchiveDatasetId, List<BlockRange>> coverage = new EnumMap<>(ArchiveDatasetId.class);
        final Map<ArchiveDatasetId, List<ArchiveCommitBoundary>> boundaries = new EnumMap<>(ArchiveDatasetId.class);

        @Override public ArchiveIdentity identity() {
            return new ArchiveIdentity(UUID.randomUUID(), "fixture", 1, 1, "fixture");
        }
        @Override public ArchiveCapabilities capabilities() {
            return new ArchiveCapabilities(true, false, false, false, false);
        }
        @Override public ArchiveWriteSession begin(ArchiveJob job) { throw new UnsupportedOperationException(); }
        @Override public Optional<ArchiveReceipt> findReceipt(UUID jobId) { return Optional.empty(); }
        @Override public ArchiveCoverage coverage(ArchiveDatasetId dataset) {
            return new ArchiveCoverage(dataset, 1, 5,
                    coverage.getOrDefault(dataset, List.of()).stream().map(value -> (ArchiveRange) value).toList());
        }
        @Override public ArchiveCoverage coverage(ArchiveReadSession session, ArchiveDatasetId dataset) {
            return coverage(dataset);
        }
        @Override public Optional<ArchiveCommitBoundary> latestBlockBoundary(ArchiveReadSession session,
                ArchiveDatasetId dataset, BlockRange range, OptionalLong atOrBeforeSlot) {
            return boundaries.getOrDefault(dataset, List.of()).stream()
                    .filter(value -> value.range().endInclusive() >= range.startInclusive()
                            && value.range().endInclusive() <= range.endInclusive())
                    .filter(value -> atOrBeforeSlot.isEmpty()
                            || value.anchors().endSlot() <= atOrBeforeSlot.getAsLong())
                    .max(java.util.Comparator.comparingLong(value -> value.range().endInclusive()));
        }
        @Override public ArchiveReadSession openReadSession() { return new ArchiveReadSession() {
            @Override public long generation() { return 5; }
            @Override public void close() { }
        }; }
        @Override public void invalidate(ArchiveDatasetId dataset, ArchiveRange range) { }
        @Override public int invalidateEpochJobsAfterSlot(ArchiveDatasetId dataset, long rollbackSlot) { return 0; }
        @Override public void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff) { }
        @Override public void maintain(ArchiveMaintenanceBudget budget) { }
        @Override public ArchiveHealth health() { return ArchiveHealth.healthy(); }
        @Override public void close() { }
    }
}
