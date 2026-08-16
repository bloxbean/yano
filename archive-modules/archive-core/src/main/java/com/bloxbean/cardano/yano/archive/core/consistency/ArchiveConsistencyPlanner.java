package com.bloxbean.cardano.yano.archive.core.consistency;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveBlockPoint;
import com.bloxbean.cardano.yano.archive.api.ArchiveCommitBoundary;
import com.bloxbean.cardano.yano.archive.api.ArchiveConsistencyPoint;
import com.bloxbean.cardano.yano.archive.api.ArchiveConsistentRead;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.SourceKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Computes a common finalized boundary without coordinating projection workers. */
public final class ArchiveConsistencyPlanner {
    private ArchiveConsistencyPlanner() {
    }

    public static ArchiveConsistentRead open(
            ArchiveBackend backend,
            Set<ArchiveDatasetId> datasets,
            long fromBlock,
            OptionalLong atOrBeforeBlock,
            OptionalLong atOrBeforeSlot) {
        Objects.requireNonNull(backend, "backend");
        ArchiveReadSession session = backend.openReadSession();
        try {
            return new ArchiveConsistentRead(session,
                    plan(backend, session, datasets, fromBlock, atOrBeforeBlock, atOrBeforeSlot));
        } catch (RuntimeException | Error e) {
            session.close();
            throw e;
        }
    }

    public static ArchiveConsistencyPoint plan(
            ArchiveBackend backend,
            ArchiveReadSession session,
            Set<ArchiveDatasetId> datasets,
            long fromBlock,
            OptionalLong atOrBeforeBlock,
            OptionalLong atOrBeforeSlot) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(datasets, "datasets");
        Objects.requireNonNull(atOrBeforeBlock, "atOrBeforeBlock");
        Objects.requireNonNull(atOrBeforeSlot, "atOrBeforeSlot");
        if (datasets.isEmpty() || fromBlock < 0
                || atOrBeforeBlock.isPresent() && atOrBeforeBlock.getAsLong() < fromBlock
                || atOrBeforeSlot.isPresent() && atOrBeforeSlot.getAsLong() < 0) {
            throw new IllegalArgumentException("invalid archive consistency request");
        }

        List<ArchiveDatasetId> ordered = datasets.stream()
                .peek(dataset -> {
                    if (dataset.sourceKind() != SourceKind.BLOCK) {
                        throw new IllegalArgumentException("common block reads cannot include " + dataset.logicalName());
                    }
                })
                .sorted(Comparator.comparing(ArchiveDatasetId::logicalName))
                .toList();

        List<BlockRange> common = null;
        var versions = new EnumMap<ArchiveDatasetId, Integer>(ArchiveDatasetId.class);
        for (ArchiveDatasetId dataset : ordered) {
            ArchiveCoverage coverage = backend.coverage(session, dataset);
            if (coverage.revision() != session.generation()) {
                throw new ArchiveStoreException("coverage generation changed inside pinned read session");
            }
            versions.put(dataset, coverage.projectionVersion());
            List<BlockRange> ranges = coverage.completeRanges().stream()
                    .map(range -> (BlockRange) range)
                    .toList();
            common = common == null ? ranges : intersect(common, ranges);
        }

        BlockRange selected = common == null ? null : common.stream()
                .filter(range -> range.startInclusive() <= fromBlock && fromBlock <= range.endInclusive())
                .findFirst().orElse(null);
        if (selected == null) {
            throw new ArchiveStoreException("selected datasets have no common finalized coverage from block " + fromBlock);
        }

        long upperBlock = atOrBeforeBlock.isPresent()
                ? Math.min(selected.endInclusive(), atOrBeforeBlock.getAsLong())
                : selected.endInclusive();
        BlockRange candidateRange = new BlockRange(fromBlock, upperBlock);
        ArchiveCommitBoundary boundary = ordered.stream()
                .map(dataset -> backend.latestBlockBoundary(
                        session, dataset, candidateRange, atOrBeforeSlot).orElse(null))
                .filter(Objects::nonNull)
                .max(Comparator.comparingLong(value -> value.range().endInclusive()))
                .orElseThrow(() -> new ArchiveStoreException(
                        "selected datasets have no committed boundary for the requested point"));

        long block = boundary.range().endInclusive();
        return new ArchiveConsistencyPoint(session.generation(), new BlockRange(fromBlock, block),
                new ArchiveBlockPoint(block, boundary.anchors().endSlot(), boundary.anchors().endHash()), versions);
    }

    static List<BlockRange> intersect(List<BlockRange> left, List<BlockRange> right) {
        List<BlockRange> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            BlockRange a = left.get(i);
            BlockRange b = right.get(j);
            long start = Math.max(a.startInclusive(), b.startInclusive());
            long end = Math.min(a.endInclusive(), b.endInclusive());
            if (start <= end) result.add(new BlockRange(start, end));
            if (a.endInclusive() < b.endInclusive()) i++; else j++;
        }
        return List.copyOf(result);
    }
}
