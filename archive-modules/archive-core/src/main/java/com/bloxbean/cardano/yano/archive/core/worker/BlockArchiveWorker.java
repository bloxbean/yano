package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveBatchCapacityException;
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
import com.bloxbean.cardano.yano.archive.core.dataset.StatefulBlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;

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
    private int preferredBlocksPerBatch;
    private int successfulBatchesAtPreferredSize;

    private static final int SUCCESSES_BEFORE_GROWTH_PROBE = 3;

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
        this.preferredBlocksPerBatch = config.maxBlocksPerBatch();
    }

    /** Processes at most one configured batch and returns the last committed block. */
    public long runBatch(BlockArchiveDataset<B> dataset, long requestedStart, long finalizedEnd) {
        Objects.requireNonNull(dataset, "dataset");
        reconcileCommittedTip(dataset, requestedStart);
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
        long alreadyCoveredEnd = backend.coverage(dataset.dataset()).completeRanges().stream()
                .filter(range -> range.startInclusive() <= start && range.endInclusive() >= start)
                .mapToLong(com.bloxbean.cardano.yano.archive.api.ArchiveRange::endInclusive)
                .findFirst().orElse(-1);
        if (alreadyCoveredEnd >= start) {
            return advanceThroughCovered(dataset, start, Math.min(finalizedEnd,
                    Math.min(alreadyCoveredEnd, start + config.maxBlocksPerBatch() - 1L)));
        }

        long end = Math.min(finalizedEnd, Math.addExact(start, preferredBlocksPerBatch - 1L));
        while (true) {
            metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.RUNNING,
                    previous, finalizedEnd - previous, "deriving " + start + ".." + end);
            try (var lease = source.acquire(start, end, Instant.now().plus(leaseDuration))) {
                List<BlockSourceContext<B>> blocks = new ArrayList<>();
                for (long block = start; block <= end; block++) {
                    long currentBlock = block;
                    BlockSourceContext<B> context = source.readCanonical(currentBlock)
                            .orElseThrow(() -> new ArchiveStoreException("canonical body unavailable for block "
                                    + currentBlock));
                    blocks.add(context);
                }
                verifyParentChain(blocks, progress.load(dataset.dataset(), ArchiveTrack.BACKFILL).orElse(null));
                BlockSourceContext<B> first = blocks.getFirst();
                BlockSourceContext<B> last = blocks.getLast();
                recheck(first);
                recheck(last);
                ArchiveJob job = ArchiveJob.deterministic(network, dataset.dataset(), dataset.projectionVersion(),
                        new BlockRange(start, end), new ArchiveRangeAnchor(first.slot(), first.blockHash(),
                                last.slot(), last.blockHash()), "canonical-block-v1");
                List<ArchiveRow> rows = new ArrayList<>();
                if (dataset instanceof StatefulBlockArchiveDataset<B> stateful) {
                    stateful.beginBatch(job, List.copyOf(blocks));
                }
                for (BlockSourceContext<B> context : blocks) {
                    dataset.derive(job, context, row -> {
                        if (rows.size() >= config.maxRowsPerBatch()) {
                            throw new RowLimitExceeded();
                        }
                        rows.add(row);
                    });
                }
                ArchiveReceipt receipt;
                try (var write = backend.begin(job)) {
                    rows.forEach(write::append);
                    recheck(first);
                    recheck(last);
                    receipt = write.commit();
                }
                if (dataset instanceof StatefulBlockArchiveDataset<B> stateful) {
                    stateful.commitBatch(receipt);
                } else {
                    progress.save(new ArchiveProgress(dataset.dataset(), ArchiveTrack.BACKFILL,
                            end, last.slot(), last.blockHash(), receipt.backendGeneration()), receipt);
                }
                metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.IDLE,
                        end, finalizedEnd - end, "committed generation " + receipt.backendGeneration());
                recordSuccessfulBatch(Math.toIntExact(end - start + 1), end < finalizedEnd);
                return end;
            } catch (RowLimitExceeded | ArchiveBatchCapacityException e) {
                abortStateful(dataset);
                String limit = e instanceof RowLimitExceeded ? "row limit" : "backend capacity";
                if (end == start) {
                    metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.DEGRADED,
                            previous, finalizedEnd - previous, "single block exceeds archive " + limit);
                    throw new ArchiveStoreException("archive " + limit + " exceeded for block " + start, e);
                }
                long attemptedEnd = end;
                end = start + (end - start) / 2;
                preferredBlocksPerBatch = Math.min(preferredBlocksPerBatch,
                        Math.toIntExact(end - start + 1));
                successfulBatchesAtPreferredSize = 0;
                metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.RUNNING,
                        previous, finalizedEnd - previous, limit + " exceeded for " + start + ".."
                                + attemptedEnd + "; retrying " + start + ".." + end);
            } catch (RuntimeException e) {
                abortStateful(dataset);
                metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.DEGRADED,
                        previous, finalizedEnd - previous, e.getMessage());
                throw e;
            }
        }
    }

    private void recordSuccessfulBatch(int committedBlocks, boolean moreFinalizedBlocksAvailable) {
        if (!moreFinalizedBlocksAvailable || committedBlocks != preferredBlocksPerBatch
                || preferredBlocksPerBatch >= config.maxBlocksPerBatch()) {
            successfulBatchesAtPreferredSize = 0;
            return;
        }
        successfulBatchesAtPreferredSize++;
        if (successfulBatchesAtPreferredSize < SUCCESSES_BEFORE_GROWTH_PROBE) return;
        preferredBlocksPerBatch = (int) Math.min(config.maxBlocksPerBatch(),
                Math.multiplyExact((long) preferredBlocksPerBatch, 2L));
        successfulBatchesAtPreferredSize = 0;
    }

    private long advanceThroughCovered(BlockArchiveDataset<B> dataset, long start, long end) {
        try (var lease = source.acquire(start, end, Instant.now().plus(leaseDuration))) {
            List<BlockSourceContext<B>> blocks = new ArrayList<>();
            for (long block = start; block <= end; block++) {
                long current = block;
                blocks.add(source.readCanonical(current).orElseThrow(() ->
                        new ArchiveStoreException("canonical covered body unavailable for block " + current)));
            }
            verifyParentChain(blocks, progress.load(dataset.dataset(), ArchiveTrack.BACKFILL).orElse(null));
            BlockSourceContext<B> first = blocks.getFirst();
            BlockSourceContext<B> last = blocks.getLast();
            recheck(first);
            recheck(last);
            long generation;
            try (var read = backend.openReadSession()) { generation = read.generation(); }
            if (dataset instanceof StatefulBlockArchiveDataset<B> stateful) {
                ArchiveJob job = ArchiveJob.deterministic(network, dataset.dataset(), dataset.projectionVersion(),
                        new BlockRange(start, end), new ArchiveRangeAnchor(first.slot(), first.blockHash(),
                        last.slot(), last.blockHash()), "covered-canonical-v1");
                stateful.beginBatch(job, List.copyOf(blocks));
                try {
                    for (BlockSourceContext<B> block : blocks) dataset.derive(job, block, ignored -> { });
                    stateful.commitCoveredBatch(generation);
                } catch (RuntimeException e) {
                    stateful.abortBatch();
                    throw e;
                }
            } else if (progress instanceof RocksDbHotHistoryStore hot) {
                hot.saveCoveredProgress(new ArchiveProgress(dataset.dataset(), ArchiveTrack.BACKFILL,
                        end, last.slot(), last.blockHash(), generation));
            } else {
                throw new ArchiveStoreException("covered range requires a durable hot progress store");
            }
            metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.IDLE,
                    end, 0, "advanced through live-promoted coverage");
            return end;
        }
    }

    private void reconcileCommittedTip(BlockArchiveDataset<B> dataset, long requestedStart) {
        ArchiveProgress current = progress.load(dataset.dataset(), ArchiveTrack.BACKFILL).orElse(null);
        if (current == null) return;
        var canonical = source.canonicalReference(current.coordinate()).orElse(null);
        if (canonical != null && canonical.slot() == current.slot()
                && Arrays.equals(canonical.blockHash(), current.blockHash())) return;
        // Backend invalidation is job-atomic. Rebuilding from the dataset's
        // activation anchor is slower than partial surgery but cannot leave a
        // prefix whose receipt/coverage was removed with an overlapping job.
        backend.invalidate(dataset.dataset(), new BlockRange(requestedStart, current.coordinate()));
        if (progress instanceof RocksDbHotHistoryStore hot) {
            try {
                hot.resetTrackFrom(dataset.dataset(), ArchiveTrack.BACKFILL, requestedStart);
            } catch (IllegalStateException e) {
                throw new BackfillActivationInvalidatedException(dataset.dataset(), requestedStart, e);
            }
        } else {
            throw new ArchiveStoreException("canonical history changed but progress store cannot re-activate");
        }
        metrics.update(dataset.dataset(), ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.RUNNING,
                requestedStart - 1, 0, "canonical rollback detected; rebuilding from activation anchor");
    }

    @SuppressWarnings("unchecked")
    private void abortStateful(BlockArchiveDataset<B> dataset) {
        if (dataset instanceof StatefulBlockArchiveDataset<?> stateful) {
            ((StatefulBlockArchiveDataset<B>) stateful).abortBatch();
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

    private void verifyParentChain(List<BlockSourceContext<B>> blocks, ArchiveProgress prior) {
        if (blocks.isEmpty()) return;
        if (prior != null && blocks.getFirst().blockNumber() == prior.coordinate() + 1
                && !Arrays.equals(blocks.getFirst().parentHash(), prior.blockHash())) {
            throw new ArchiveStoreException("archive batch does not extend its committed parent");
        }
        for (int index = 1; index < blocks.size(); index++) {
            if (!Arrays.equals(blocks.get(index).parentHash(), blocks.get(index - 1).blockHash())) {
                throw new ArchiveStoreException("mixed canonical forks in archive batch at block "
                        + blocks.get(index).blockNumber());
            }
        }
    }

    private static final class RowLimitExceeded extends RuntimeException { }
}
