package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/** Read-only projection of a shared canonical block source into dataset facts. */
public final class MappingBlockArchiveSource<S, T> implements BlockArchiveSource<T> {
    private final BlockArchiveSource<S> source;
    private final Function<BlockSourceContext<S>, BlockSourceContext<T>> mapper;

    public MappingBlockArchiveSource(BlockArchiveSource<S> source,
                                     Function<BlockSourceContext<S>, BlockSourceContext<T>> mapper) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<BlockSourceContext<T>> readCanonical(long blockNumber) {
        return source.readCanonical(blockNumber).map(mapper);
    }

    @Override
    public Optional<CanonicalBlockReference> canonicalReference(long blockNumber) {
        return source.canonicalReference(blockNumber);
    }

    @Override
    public boolean extendsCanonicalParent(byte[] predecessorHash, BlockSourceContext<T> current) {
        if (BlockArchiveSource.super.extendsCanonicalParent(predecessorHash, current)) return true;
        // A mapped fact no longer carries the decoded Block needed for the
        // exceptional Byron EBB bridge. Reuse the shared canonical source; in
        // a projection cycle this is a cache hit, not another decode.
        return source.readCanonical(current.blockNumber())
                .map(sourceCurrent -> source.extendsCanonicalParent(predecessorHash, sourceCurrent))
                .orElse(false);
    }

    @Override
    public ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt) {
        return source.acquire(startBlock, endBlock, expiresAt);
    }

    @Override
    public long earliestRetainedBody() {
        return source.earliestRetainedBody();
    }
}
