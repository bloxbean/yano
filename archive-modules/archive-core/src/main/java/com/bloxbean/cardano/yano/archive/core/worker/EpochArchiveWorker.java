package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.EpochRange;
import com.bloxbean.cardano.yano.archive.core.dataset.EpochArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.source.EpochArchiveSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Streams one immutable epoch job without holding the full epoch in heap. */
public final class EpochArchiveWorker<T> {
    private final EpochArchiveSource<T> source;
    private final EpochArchiveDataset<T> dataset;
    private final ArchiveBackend backend;
    private final ArchiveProgressStore progress;
    private final int pageSize;
    private final int maxRows;

    public EpochArchiveWorker(EpochArchiveSource<T> source, EpochArchiveDataset<T> dataset,
                              ArchiveBackend backend, ArchiveProgressStore progress,
                              int pageSize, int maxRows) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.dataset = java.util.Objects.requireNonNull(dataset, "dataset");
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.progress = java.util.Objects.requireNonNull(progress, "progress");
        if (source.dataset() != dataset.dataset() || pageSize < 1 || maxRows < pageSize) {
            throw new IllegalArgumentException("invalid epoch worker configuration");
        }
        this.pageSize = pageSize;
        this.maxRows = maxRows;
    }

    public boolean runNext() {
        return runNext(Long.MAX_VALUE);
    }

    public boolean runNext(long maximumBoundaryBlock) {
        var pending = source.pending(16).stream()
                .filter(job -> job.boundaryBlockNumber() <= maximumBoundaryBlock)
                .limit(1).toList();
        if (pending.isEmpty()) return false;
        var sourceJob = pending.getFirst();
        ArchiveJob job = ArchiveJob.deterministic(sourceJob.networkIdentity(), dataset.dataset(),
                dataset.projectionVersion(), new EpochRange(sourceJob.epoch(), sourceJob.epoch()),
                new ArchiveRangeAnchor(sourceJob.boundarySlot(), sourceJob.boundaryBlockHash(),
                        sourceJob.boundarySlot(), sourceJob.boundaryBlockHash()), sourceJob.sourceStateVersion());
        try (var lease = source.acquire(sourceJob, Instant.now().plusSeconds(300));
             var write = backend.begin(job)) {
            Optional<String> cursor = Optional.empty();
            int rows = 0;
            do {
                var page = source.read(sourceJob, cursor, pageSize, lease);
                List<ArchiveRow> derived = new ArrayList<>();
                dataset.derive(job, page, derived::add);
                rows = Math.addExact(rows, derived.size());
                if (rows > maxRows) throw new IllegalStateException("epoch archive row limit exceeded");
                derived.forEach(write::append);
                cursor = page.nextCursor();
            } while (cursor.isPresent());
            var receipt = write.commit();
            progress.save(new ArchiveProgress(dataset.dataset(), ArchiveTrack.BACKFILL, sourceJob.epoch(),
                    sourceJob.boundarySlot(), sourceJob.boundaryBlockHash(), receipt.backendGeneration()), receipt);
            source.acknowledge(sourceJob);
            return true;
        }
    }
}
