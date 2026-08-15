package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded cycle-local canonical block cache. A coordinator opens one cycle,
 * lets independently registered projections share decoded blocks, joins them,
 * and then clears every reference before processing the next cycle.
 */
public final class CycleCachingBlockArchiveSource<B> implements BlockArchiveSource<B> {
    private final BlockArchiveSource<B> delegate;
    private final int maximumEntries;
    private final ConcurrentHashMap<Long, Optional<BlockSourceContext<B>>> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder decoded = new LongAdder();
    private final LongAdder hits = new LongAdder();

    public CycleCachingBlockArchiveSource(BlockArchiveSource<B> delegate, int maximumEntries) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        this.maximumEntries = maximumEntries;
    }

    public void beginCycle() {
        if (!active.compareAndSet(false, true)) throw new IllegalStateException("archive source cycle already active");
        cache.clear();
    }

    public CycleStats endCycle() {
        if (!active.compareAndSet(true, false)) return new CycleStats(0, 0);
        cache.clear();
        long cycleDecoded = decoded.sumThenReset();
        long cycleHits = hits.sumThenReset();
        return new CycleStats(cycleDecoded, cycleHits);
    }

    @Override
    public Optional<BlockSourceContext<B>> readCanonical(long blockNumber) {
        if (!active.get()) return delegate.readCanonical(blockNumber);
        Optional<BlockSourceContext<B>> existing = cache.get(blockNumber);
        if (existing != null) {
            hits.increment();
            return existing;
        }
        if (cache.size() >= maximumEntries) {
            decoded.increment();
            return delegate.readCanonical(blockNumber);
        }
        AtomicBoolean created = new AtomicBoolean();
        Optional<BlockSourceContext<B>> result = cache.computeIfAbsent(blockNumber, key -> {
            created.set(true);
            decoded.increment();
            return delegate.readCanonical(key);
        });
        if (!created.get()) hits.increment();
        return result;
    }

    @Override
    public Optional<CanonicalBlockReference> canonicalReference(long blockNumber) {
        return delegate.canonicalReference(blockNumber);
    }

    @Override
    public ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt) {
        return delegate.acquire(startBlock, endBlock, expiresAt);
    }

    @Override
    public long earliestRetainedBody() {
        return delegate.earliestRetainedBody();
    }

    public record CycleStats(long decodedBlocks, long cacheHits) { }
}
