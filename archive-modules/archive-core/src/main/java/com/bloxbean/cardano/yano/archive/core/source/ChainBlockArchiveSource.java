package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.api.BlockBodyRetentionBoundary;
import com.bloxbean.cardano.yano.api.ChainBlockReader;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryStore;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.Block;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/** Narrow read-only adapter from core block capabilities to the archive worker. */
public final class ChainBlockArchiveSource<B> implements BlockArchiveSource<B> {
    private final ChainBlockReader reader;
    private final CanonicalBlockDecoder<B> decoder;
    private final HotHistoryStore leases;

    public ChainBlockArchiveSource(ChainBlockReader reader, CanonicalBlockDecoder<B> decoder,
                                   HotHistoryStore leases) {
        this.reader = java.util.Objects.requireNonNull(reader, "reader");
        this.decoder = java.util.Objects.requireNonNull(decoder, "decoder");
        this.leases = java.util.Objects.requireNonNull(leases, "leases");
    }

    @Override
    public Optional<BlockSourceContext<B>> readCanonical(long blockNumber) {
        return reader.getCanonicalBlockReference(blockNumber).flatMap(reference -> {
            byte[] body = reader.getBlockByNumber(blockNumber);
            if (body == null) return Optional.empty();
            BlockSourceContext<B> decoded = decoder.decode(blockNumber, reference, body);
            if (decoded.blockNumber() != reference.blockNumber() || decoded.slot() != reference.slot()
                    || !java.util.Arrays.equals(decoded.blockHash(), reference.blockHash())) {
                throw new ArchiveStoreException("decoded block does not match canonical reference " + blockNumber);
            }
            return Optional.of(decoded);
        });
    }

    @Override
    public Optional<com.bloxbean.cardano.yano.api.CanonicalBlockReference> canonicalReference(long blockNumber) {
        return reader.getCanonicalBlockReference(blockNumber);
    }

    @Override
    public boolean extendsCanonicalParent(byte[] predecessorHash, BlockSourceContext<B> current) {
        if (BlockArchiveSource.super.extendsCanonicalParent(predecessorHash, current)) return true;
        // The EBB lookup is deliberately exceptional: only a failed direct
        // parent check in Byron can need this bridge.
        // The decoded source block is authoritative here. The chain-state era
        // helper can be unavailable for raw Byron storage representations.
        if (!(current.block() instanceof Block block) || block.getEra() != Era.Byron) return false;
        return reader.getByronEpochBoundaryBlockAtOrBefore(current.slot())
                .filter(ebb -> Arrays.equals(ebb.blockHash(), current.parentHash())
                        && Arrays.equals(ebb.parentHash(), predecessorHash))
                .isPresent();
    }

    @Override
    public ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt) {
        long earliest = earliestRetainedBody();
        if (startBlock < earliest) {
            throw new ArchiveStoreException("required block bodies already pruned: start=" + startBlock
                    + ", earliest=" + earliest);
        }
        return leases.acquireBlockBodyLease(startBlock, endBlock, expiresAt);
    }

    @Override
    public long earliestRetainedBody() {
        return reader.getEarliestRetainedBodyBlockNumber().orElse(Long.MAX_VALUE);
    }

    public BlockBodyRetentionBoundary retentionBoundary() { return leases; }
}
