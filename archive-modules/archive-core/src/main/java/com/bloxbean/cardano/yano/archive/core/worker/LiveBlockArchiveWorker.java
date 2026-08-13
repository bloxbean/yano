package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.*;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;

import java.time.Instant;
import java.util.*;

/** Bounded near-tip projection for resolver-independent datasets. */
public final class LiveBlockArchiveWorker<B> {
    private final ArchiveNetworkIdentity network;
    private final BlockArchiveSource<B> source;
    private final RocksDbHotHistoryStore hot;
    private final ArchiveWorkerConfig config;
    private final ArchiveWorkerMetrics metrics;

    public LiveBlockArchiveWorker(ArchiveNetworkIdentity network, BlockArchiveSource<B> source,
                                  RocksDbHotHistoryStore hot, ArchiveWorkerConfig config,
                                  ArchiveWorkerMetrics metrics) {
        this.network = network; this.source = source; this.hot = hot; this.config = config; this.metrics = metrics;
    }

    public long runBatch(BlockArchiveDataset<B> dataset, long activation, long tip) {
        ArchiveProgress progress = hot.load(dataset.dataset(), ArchiveTrack.LIVE).orElse(null);
        long previous = progress == null ? activation - 1 : progress.coordinate();
        if (progress != null) {
            var canonical = source.readCanonical(previous).orElse(null);
            if (canonical == null || !Arrays.equals(canonical.blockHash(), progress.blockHash())) {
                throw new LiveActivationInvalidatedException(dataset.dataset(), activation);
            }
        }
        long start = previous + 1;
        if (start > tip) return previous;
        long end = Math.min(tip, start + config.maxBlocksPerBatch() - 1L);
        List<HotBlockUpdate> updates = new ArrayList<>();
        List<BlockSourceContext<B>> blocks = new ArrayList<>();
        List<List<HotHistoryMutation>> rowsByBlock = new ArrayList<>();
        BlockSourceContext<B> last = null;
        ArchiveJob batchJob = null;
        for (long number = start; number <= end; number++) {
            long current = number;
            var context = source.readCanonical(current).orElseThrow(() ->
                    new ArchiveStoreException("canonical live body unavailable for block " + current));
            blocks.add(context);
        }
        StatefulBlockArchiveDataset<B> statefulDataset = null;
        if (dataset instanceof StatefulBlockArchiveDataset<B> stateful) {
            statefulDataset = stateful;
            var first = blocks.getFirst(); var batchLast = blocks.getLast();
            batchJob = ArchiveJob.deterministic(network, dataset.dataset(), dataset.projectionVersion(),
                    new BlockRange(start, end), new ArchiveRangeAnchor(first.slot(), first.blockHash(),
                    batchLast.slot(), batchLast.blockHash()), "live-canonical-v1");
            stateful.beginBatch(batchJob, List.copyOf(blocks));
        }
        try {
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            long number = start + blockIndex;
            long currentNumber = number;
            BlockSourceContext<B> block = blocks.get(blockIndex);
            ArchiveJob job = batchJob != null ? batchJob : ArchiveJob.deterministic(network, dataset.dataset(), dataset.projectionVersion(),
                    new BlockRange(number, number), new ArchiveRangeAnchor(block.slot(), block.blockHash(),
                            block.slot(), block.blockHash()), "live-canonical-v1");
            List<HotHistoryMutation> mutations = new ArrayList<>();
            dataset.derive(job, block, row -> {
                // Content-addressed rows are bounded by the live window and
                // may be promoted early; orphan payloads are harmless and
                // idempotent by hash.
                if (mutations.size() >= config.maxRowsPerBatch()) throw new ArchiveStoreException("live row bound exceeded");
                mutations.add(HotArchiveRows.put(dataset.dataset(), row));
            });
            updates.add(new HotBlockUpdate(new HotBlockCheckpoint(number, block.slot(), block.blockHash(),
                    block.parentHash()), mutations));
            rowsByBlock.add(List.copyOf(mutations));
            last = block;
        }
        if (dataset instanceof LiveStatefulBlockArchiveDataset<B> stateful) {
            stateful.commitLiveBatch(rowsByBlock);
        } else {
            hot.applyBlocks(dataset.dataset(), updates, new ArchiveProgress(dataset.dataset(), ArchiveTrack.LIVE,
                    end, last.slot(), last.blockHash(), 0), null);
        }
        } catch (RuntimeException e) {
            if (statefulDataset != null) statefulDataset.abortBatch();
            throw e;
        }
        metrics.update(dataset.dataset(), ArchiveTrack.LIVE, ArchiveWorkerStatus.State.IDLE,
                end, tip - end, "live projection updated");
        return end;
    }
}
