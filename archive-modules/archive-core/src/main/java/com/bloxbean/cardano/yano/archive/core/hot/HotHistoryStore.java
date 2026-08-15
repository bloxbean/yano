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
import java.util.OptionalLong;
import com.bloxbean.cardano.yano.archive.core.address.Outpoint;
import com.bloxbean.cardano.yano.archive.core.address.ResolvedOutput;
import com.bloxbean.cardano.yano.archive.core.address.SequentialOutpointResolver;
import com.bloxbean.cardano.yano.archive.core.address.SequentialPointerResolver;

/**
 * Backend-neutral ownership boundary for the complete archive hot layer.
 *
 * <p>Canonical writes cross this seam as logical fact and typed resolver/
 * pointer lifecycle operations. Physical RocksDB keys and SQLite rows remain
 * private to their implementations.</p>
 */
public interface HotHistoryStore extends ArchiveProgressStore, BlockBodyRetentionBoundary, AutoCloseable {
    void applyBlock(ArchiveDatasetId dataset, HotBlockCheckpoint block,
                    List<HotHistoryOperation> operations, ArchiveProgress progress);

    void applyBlocks(ArchiveDatasetId dataset, List<HotBlockUpdate> blocks,
                     ArchiveProgress progress, ArchiveReceipt receipt);

    void seedResolver(Iterable<SequentialOutpointResolver.Entry> outputs, boolean complete, long baseBlock);

    boolean resolverSeeded();

    OptionalLong resolverBaseBlock();

    Optional<ResolvedOutput> resolveOutput(Outpoint outpoint);

    Optional<SequentialPointerResolver.ResolvedStakeCredential> resolvePointer(
            ArchiveDatasetId dataset, SequentialPointerResolver.PointerCoordinate pointer);

    List<SequentialPointerResolver.PointerCoordinate> pointersForCredential(
            ArchiveDatasetId dataset, SequentialPointerResolver.ResolvedStakeCredential credential);

    void resetResolver(ArchiveDatasetId dataset);

    void rollbackTo(ArchiveDatasetId dataset, ArchiveTrack track, long commonBlock);

    void resetTrackFrom(ArchiveDatasetId dataset, ArchiveTrack track, long firstBlock);

    void pruneUndoThrough(ArchiveDatasetId dataset, ArchiveTrack track, long blockInclusive);

    /**
     * Removes resolver output/spend pairs whose consuming block is outside the
     * retained rollback window. Currently-unspent outputs are never removed.
     */
    void pruneResolverThrough(ArchiveDatasetId dataset, long blockInclusive);

    Optional<HotBlockCheckpoint> checkpoint(ArchiveDatasetId dataset, ArchiveTrack track, long block);

    Optional<com.bloxbean.cardano.yano.archive.api.ArchiveRecord> findFact(
            ArchiveDatasetId dataset, byte[] logicalKey);

    void deleteFacts(ArchiveDatasetId dataset, Collection<byte[]> logicalKeys);

    void clearTrack(ArchiveDatasetId dataset, ArchiveTrack track);

    HotHistorySnapshot snapshot();

    ArchiveSourceLease acquireBlockBodyLease(long startBlock, long endBlock, Instant expiresAt);

    void requireBlockBodiesFrom(ArchiveDatasetId dataset, long blockNumber);

    void releaseBlockBodyRequirement(ArchiveDatasetId dataset);

    void saveCoveredProgress(ArchiveProgress progress);

    @Override
    void close();
}
