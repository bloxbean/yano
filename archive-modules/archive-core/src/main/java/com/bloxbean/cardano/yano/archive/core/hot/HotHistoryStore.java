package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.api.BlockBodyRetentionBoundary;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgressStore;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Backend-neutral ownership boundary for the complete archive hot layer.
 *
 * <p>The byte-oriented mutation methods preserve the current RocksDB contract
 * during ADR-036 phase 1. They are deliberately contained here so workers,
 * resolvers, promotion, and runtime integration no longer depend on the
 * concrete RocksDB implementation. ADR-036 phase 2 replaces this transitional
 * mutation boundary with semantic fact and resolver lifecycle operations
 * before a relational SQLite implementation is considered conformant.</p>
 */
public interface HotHistoryStore extends ArchiveProgressStore, BlockBodyRetentionBoundary, AutoCloseable {
    void applyBlock(ArchiveDatasetId dataset, HotBlockCheckpoint block,
                    List<HotHistoryMutation> mutations, ArchiveProgress progress);

    void applyBlocks(ArchiveDatasetId dataset, List<HotBlockUpdate> blocks,
                     ArchiveProgress progress, ArchiveReceipt receipt);

    void seed(ArchiveDatasetId dataset, List<HotHistoryMutation> mutations);

    void rollbackTo(ArchiveDatasetId dataset, ArchiveTrack track, long commonBlock);

    void resetTrackFrom(ArchiveDatasetId dataset, ArchiveTrack track, long firstBlock);

    void pruneUndoThrough(ArchiveDatasetId dataset, ArchiveTrack track, long blockInclusive);

    Optional<HotBlockCheckpoint> checkpoint(ArchiveDatasetId dataset, ArchiveTrack track, long block);

    Optional<byte[]> get(ArchiveDatasetId dataset, byte[] logicalKey);

    List<HotHistorySnapshot.Entry> scanDataPrefix(ArchiveDatasetId dataset, byte[] logicalPrefix);

    void deleteData(ArchiveDatasetId dataset, Collection<byte[]> logicalKeys);

    void deleteDataPrefix(ArchiveDatasetId dataset, byte[] logicalPrefix);

    void clearTrack(ArchiveDatasetId dataset, ArchiveTrack track, Collection<byte[]> logicalDataPrefixes);

    HotHistorySnapshot snapshot();

    ArchiveSourceLease acquireBlockBodyLease(long startBlock, long endBlock, Instant expiresAt);

    void requireBlockBodiesFrom(ArchiveDatasetId dataset, long blockNumber);

    void releaseBlockBodyRequirement(ArchiveDatasetId dataset);

    void saveCoveredProgress(ArchiveProgress progress);

    @Override
    void close();
}
