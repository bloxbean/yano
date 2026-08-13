package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;

import java.time.Instant;
import java.util.Optional;

/** Canonical block-body source with a durable pruning lease. */
public interface BlockArchiveSource<B> {
    Optional<BlockSourceContext<B>> readCanonical(long blockNumber);

    ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt);

    long earliestRetainedBody();
}
