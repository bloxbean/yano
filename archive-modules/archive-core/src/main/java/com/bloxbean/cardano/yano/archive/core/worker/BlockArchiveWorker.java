package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Bounded, caller-scheduled block backfill. It never runs on a block-apply thread. */
public final class BlockArchiveWorker<B> {
    private final ArchiveNetworkIdentity network;
    private final BlockArchiveSource<B> source;
    private final ArchiveBackend backend;
    private final ArchiveProgressStore progress;
    private final ArchiveWorkerConfig config;
    private final CoreSyncView coreSync;
    private final ArchiveWorkerMetrics metrics;
    private final Duration leaseDuration;

    public BlockArchiveWorker(ArchiveNetworkIdentity network, BlockArchiveSource<B> source,
                              ArchiveBackend backend, ArchiveProgressStore progress,
                              ArchiveWorkerConfig config, CoreSyncView coreSync,
                              ArchiveWorkerMetrics metrics, Duration leaseDuration) {
        this.network = Objects.requireNonNull(network, "network");
        this.source = Objects.requireNonNull(source, "source");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.config = Objects.requireNonNull(config, "config");
        this.coreSync = Objects.requireNonNull(coreSync, "coreSync");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    }

    /** Processes at most one configured batch and returns the last committed block. */
    public long runBatch(BlockArchiveDataset<B> dataset, long requestedStart, long finalizedEnd) {
        Objects.requireNonNull(dataset, "dataset");
        long previous = progress.load(dataset.dataset(), ArchiveTrack.BACKFILL)
                .map(ArchiveProgress::coordinate).orElse(requestedStart - 1);
        long start = Math.max(requestedStart, previous + 1);
        if (start > finalizedEnd) {
            metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.IDLE,
                    previous, 0, "caught up to finalized boundary");
            return previous;
        }
        if (coreSync.lag() > config.bulkPauseCoreLagBlocks()) {
            metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL,
                    ArchiveWorkerStatus.State.PAUSED_CORE_LAG, previous, coreSync.lag(),
                    "core sync has priority");
            return previous;
        }

        long end = Math.min(finalizedEnd, Math.addExact(start, config.maxBlocksPerBatch() - 1L));
        metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.RUNNING,
                previous, finalizedEnd - previous, "deriving " + start + ".." + end);
        try (var lease = source.acquire(start, end, Instant.now().plus(leaseDuration))) {
            List<BlockSourceContext<B>> blocks = new ArrayList<>();
            List<ArchiveRow> rows = new ArrayList<>();
            for (long block = start; block <= end; block++) {
                long currentBlock = block;
                BlockSourceContext<B> context = source.readCanonical(currentBlock)
                        .orElseThrow(() -> new ArchiveStoreException("canonical body unavailable for block "
                                + currentBlock));
                blocks.add(context);
                dataset.derive(context, row -> {
                    if (rows.size() >= config.maxRowsPerBatch()) {
                        throw new RowLimitExceeded();
                    }
                    rows.add(row);
                });
            }
            BlockSourceContext<B> first = blocks.getFirst();
            BlockSourceContext<B> last = blocks.getLast();
            recheck(first);
            recheck(last);
            ArchiveJob job = ArchiveJob.deterministic(network, dataset.dataset(), dataset.projectionVersion(),
                    new BlockRange(start, end), new ArchiveRangeAnchor(first.slot(), first.blockHash(),
                            last.slot(), last.blockHash()), "canonical-block-v1");
            ArchiveReceipt receipt;
            try (var write = backend.begin(job)) {
                rows.forEach(write::append);
                recheck(first);
                recheck(last);
                receipt = write.commit();
            }
            progress.save(new ArchiveProgress(dataset.dataset(), ArchiveTrack.BACKFILL,
                    end, last.slot(), last.blockHash(), receipt.backendGeneration()), receipt);
            metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.IDLE,
                    end, finalizedEnd - end, "committed generation " + receipt.backendGeneration());
            return end;
        } catch (RowLimitExceeded e) {
            metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.DEGRADED,
                    previous, finalizedEnd - previous, "row limit exceeded; reduce block batch size");
            throw new ArchiveStoreException("archive row limit exceeded before a complete block range", e);
        } catch (RuntimeException e) {
            metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.DEGRADED,
                    previous, finalizedEnd - previous, e.getMessage());
            throw e;
        }
    }

    private void recheck(BlockSourceContext<B> expected) {
        BlockSourceContext<B> current = source.readCanonical(expected.blockNumber())
                .orElseThrow(() -> new ArchiveStoreException("canonical anchor disappeared at block "
                        + expected.blockNumber()));
        if (current.slot() != expected.slot() || !Arrays.equals(current.blockHash(), expected.blockHash())) {
            throw new ArchiveStoreException("canonical anchor changed at block " + expected.blockNumber());
        }
    }

    private static final class RowLimitExceeded extends RuntimeException { }
}
