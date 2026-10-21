package org.yanoproject.runtime.ledger.canonical;

import lombok.extern.slf4j.Slf4j;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.Retainable;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One fully applied canonical ledger state: a RocksDB snapshot of the shared database plus an
 * immutable copy of the in-memory values validation reads, both taken for one published
 * {@link CanonicalTip} (ADR-056 §3).
 *
 * <p>Only {@link CanonicalStateGate#acquireSnapshot(SnapshotPurpose)} creates snapshots, and it hands
 * the caller the first reference. Every holder calls {@link #retain()} when it takes a reference and
 * {@link #release()} once when done; the RocksDB snapshot is freed when the count reaches zero.
 * {@link #close()} is {@link #release()}, for try-with-resources.</p>
 *
 * <p>Reads go through {@link #read(String, SnapshotRead)}. After the snapshot is freed, or after
 * the gate invalidated it because the database is closing, every read returns
 * {@link Lookup.Unavailable}; a read never touches a released RocksDB snapshot.</p>
 */
@Slf4j
public final class CanonicalSnapshot implements Retainable, AutoCloseable {

    /**
     * One read against the captured state.
     *
     * @param <T> value type
     */
    @FunctionalInterface
    public interface SnapshotRead<T> {
        Lookup<T> read(CanonicalSnapshotSource.Captured state) throws Exception;
    }

    private final CanonicalStateGate gate;
    private final CanonicalTip tip;
    private final SnapshotPurpose purpose;
    private final CanonicalSnapshotSource.Captured state;
    private final long createdNanos = System.nanoTime();
    private final AtomicInteger refCount = new AtomicInteger(1);
    // Reads hold the read lock; freeing and invalidation hold the write lock, so native resources are
    // never released under an in-flight read.
    private final ReentrantReadWriteLock access = new ReentrantReadWriteLock();
    private boolean resourcesReleased;
    // Derived values computed once per snapshot (for example the pool VRF index). Only Present
    // results are kept; the snapshot is immutable, so they never go stale.
    private final ConcurrentHashMap<String, Lookup<?>> memo = new ConcurrentHashMap<>();
    private volatile String unavailableReason;
    // False for a capture taken inside a write section (captureInWriteSection): its state is not the published
    // generation's, so generation-scoped values are never shared with (or taken from) published snapshots.
    private final boolean publishedState;

    CanonicalSnapshot(CanonicalStateGate gate, CanonicalTip tip, SnapshotPurpose purpose,
                      CanonicalSnapshotSource.Captured state) {
        this(gate, tip, purpose, state, true);
    }

    CanonicalSnapshot(CanonicalStateGate gate, CanonicalTip tip, SnapshotPurpose purpose,
                      CanonicalSnapshotSource.Captured state, boolean publishedState) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.tip = Objects.requireNonNull(tip, "tip");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.state = Objects.requireNonNull(state, "state");
        this.publishedState = publishedState;
    }

    /**
     * @return true when this snapshot holds the state of its published generation; false for a pre-block capture
     *         taken inside a write section ({@link CanonicalStateGate#captureInWriteSection}), whose tip is the
     *         published parent but whose state already includes the section's earlier commits (an epoch boundary)
     */
    public boolean publishedState() {
        return publishedState;
    }

    /** @return the canonical generation this snapshot belongs to */
    public long generation() {
        return tip.generation();
    }

    /** @return the published tip this snapshot was captured at */
    public CanonicalTip tip() {
        return tip;
    }

    /**
     * @return the epoch of {@code slot} by the gate's slot-to-epoch function (-1 when none is
     *         configured); pure, does not read the snapshot
     */
    int epochOfSlot(long slot) {
        return gate.epochOf(slot);
    }

    /** @return the first slot of {@code epoch} by the gate's configuration (-1 when unknown); pure */
    long epochStartSlot(int epoch) {
        return gate.epochStartSlot(epoch);
    }

    /**
     * A value derived from this snapshot's state, shared with every snapshot of the same generation. A pre-block
     * capture ({@link #publishedState()} false) computes its own, never shared.
     */
    <T> Lookup<T> generationMemoized(String key, CanonicalStateGate.Computation<T> compute) throws Exception {
        if (!publishedState) {
            return compute.compute();
        }
        return gate.generationMemoized(generation(), key, compute);
    }

    /** @return the purpose declared by the acquirer of the first reference */
    public SnapshotPurpose purpose() {
        return purpose;
    }

    /** @return milliseconds since capture (for the shadow max-age policy) */
    public long ageMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdNanos);
    }

    /** @return the current reference count; 0 once freed */
    public int refCount() {
        return refCount.get();
    }

    /** @return true while reads can still be answered */
    public boolean isReadable() {
        return unavailableReason == null && refCount.get() > 0;
    }

    /**
     * Takes one more reference.
     *
     * @throws IllegalStateException if the snapshot has already been freed
     */
    @Override
    public CanonicalSnapshot retain() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("Canonical snapshot generation " + generation() + " is already freed");
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return this;
            }
        }
    }

    /**
     * Drops one reference and frees the snapshot when none remain. Releasing an already freed
     * snapshot is a caller bug; it is logged and ignored so the count never goes negative and the
     * RocksDB snapshot is never released twice.
     */
    @Override
    public void release() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                log.warn("Ignoring release of already freed canonical snapshot (generation {})", generation());
                return;
            }
            if (refCount.compareAndSet(current, current - 1)) {
                if (current == 1) {
                    free("canonical snapshot released");
                    gate.onSnapshotFreed(this);
                }
                return;
            }
        }
    }

    /** Same as {@link #release()}. */
    @Override
    public void close() {
        release();
    }

    /**
     * Runs one read against the captured state.
     *
     * @param what description used in failure reasons
     * @param read the read
     * @return the read's outcome; {@link Lookup.Unavailable} when the snapshot is no longer readable or
     *         the read throws (RocksDB or decoding failure)
     */
    public <T> Lookup<T> read(String what, SnapshotRead<T> read) {
        Objects.requireNonNull(read, "read");
        access.readLock().lock();
        try {
            String reason = unavailableReason;
            if (reason != null) {
                return Lookup.unavailable(what + ": " + reason);
            }
            Lookup<T> result = read.read(state);
            return Objects.requireNonNull(result, "snapshot read returned null");
        } catch (Exception e) {
            return Lookup.unavailable(what + " failed at generation " + generation() + ": " + e);
        } finally {
            access.readLock().unlock();
        }
    }

    /**
     * Like {@link #read(String, SnapshotRead)}, but computes a Present result at most once per
     * snapshot and serves it from memory afterwards (only while the snapshot is readable).
     *
     * @param key cache key, unique per derived value
     */
    @SuppressWarnings("unchecked")
    public <T> Lookup<T> memoized(String key, String what, SnapshotRead<T> read) {
        Objects.requireNonNull(key, "key");
        return read(what, state -> {
            Lookup<?> cached = memo.get(key);
            if (cached != null) {
                return (Lookup<T>) cached;
            }
            Lookup<T> result = Objects.requireNonNull(read.read(state), "snapshot read returned null");
            if (result.isPresent()) {
                memo.putIfAbsent(key, result);
            }
            return result;
        });
    }

    /**
     * Frees the native resources and makes every later read unavailable, without touching the
     * reference count. Called by the gate before the database closes or is replaced.
     */
    void invalidate(String reason) {
        free(reason);
    }

    private void free(String reason) {
        access.writeLock().lock();
        try {
            if (unavailableReason == null) {
                unavailableReason = reason;
            }
            memo.clear();
            if (!resourcesReleased) {
                resourcesReleased = true;
                try {
                    state.release().run();
                } catch (RuntimeException e) {
                    log.warn("Failed to release canonical snapshot resources (generation {}): {}",
                            generation(), e.toString());
                }
            }
        } finally {
            access.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "CanonicalSnapshot[generation=" + generation() + ", slot=" + tip.slot() + ", purpose=" + purpose
                + (publishedState ? "" : ", pre-block") + ", refCount=" + refCount.get() + "]";
    }
}
