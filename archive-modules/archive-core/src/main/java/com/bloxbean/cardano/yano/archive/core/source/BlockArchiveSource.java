package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;

import java.time.Instant;
import java.util.Optional;

/** Canonical block-body source with a durable pruning lease. */
public interface BlockArchiveSource<B> {
    Optional<BlockSourceContext<B>> readCanonical(long blockNumber);

    /** Canonical identity that remains available after the block body is pruned. */
    default Optional<CanonicalBlockReference> canonicalReference(long blockNumber) {
        return readCanonical(blockNumber).map(block -> new CanonicalBlockReference(
                block.blockNumber(), block.slot(), block.blockHash()));
    }

    ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt);

    long earliestRetainedBody();
}
