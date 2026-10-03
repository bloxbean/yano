package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.yanoproject.ledger.rules.view.Lookup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntSupplier;
import java.util.function.Consumer;
import java.util.function.IntToLongFunction;
import java.util.function.LongToIntFunction;
import java.util.function.Supplier;

/**
 * Fair read-write gate around canonical ledger application (ADR-056 §3, "Canonical snapshot
 * contract").
 *
 * <h2>Writers</h2>
 * <p>A forward block (chain store plus UTxO, account, governance and epoch-boundary events for that
 * block), a rollback, or a producer's epoch-boundary section runs inside one <em>write section</em>
 * ({@link #enterWrite()}, {@link #runWrite(Runnable)}, {@link #callWrite(Supplier)}). Sections are
 * reentrant, so nested apply paths (for example the compensating rollback inside a failed block
 * apply) join the outer section. When the outermost section ends, the gate publishes a new
 * {@link CanonicalTip} with the next generation (unless every section declared itself unchanged),
 * then releases the write lock, then runs hooks registered with {@link #runAfterWriteRelease(Runnable)}.
 * A section that ended with an exception still publishes a new generation, because stores it wrote
 * before failing (or a compensating rollback inside it) may have changed state.
 * Mempool notifications use those hooks, so the mempool never runs while the gate is held.
 * {@link #addPublicationListener publication listeners} run after those hooks, once per published
 * generation (ADR-056 §6: forward blocks, rollbacks and producer boundary sections all notify the
 * mempool after the gate is released).</p>
 *
 * <h2>Chain extension</h2>
 * <p>Two writers extend the local chain at its tip without one serializing the other through a write
 * section: the upstream header store and a slot-leader producer. {@link #chainExtensionLock()} orders them.
 * The header store holds it while it checks that a header extends the header tip and, when it does not,
 * rolls the competing local block back and stores the header; the producer only <em>tries</em> it, inside
 * its write section, before it re-checks its tip and stores a forged block. A producer therefore never
 * stores a block beside an upstream header of the same height, and never waits on a header store that is
 * itself waiting for a rollback to enter the write section.</p>
 *
 * <h2>Readers</h2>
 * <p>{@link #acquireSnapshot(SnapshotPurpose)} takes the read lock, so it waits while a writer is
 * mid-block; reads the published tip; asks the installed {@link CanonicalSnapshotSource} for one
 * RocksDB snapshot plus a copy of in-memory values; and releases the read lock. The snapshot is
 * therefore always one fully applied tip.</p>
 *
 * <h2>Bounded resources</h2>
 * <p>At most {@code yano.validation.max-live-snapshots} (default 4) snapshots should be live. Beyond
 * that, {@link SnapshotPurpose#SHADOW} acquisitions are refused; the other purposes are never
 * refused and are counted in {@link #overCapAcquisitions()}. There is no metrics registry in the
 * runtime yet, so the gauges are plain accessors ({@link #liveSnapshotCount()},
 * {@link #liveSnapshotsByGeneration()}, {@link #shadowRefusals()}).
 * TODO(ADR-056 Phase 6): export them as {@code yano_validation_*} metrics once a registry exists.</p>
 */
@Slf4j
public final class CanonicalStateGate {

    /** Default for {@code yano.validation.max-live-snapshots}. */
    public static final int DEFAULT_MAX_LIVE_SNAPSHOTS = 4;

    private static final ThreadLocal<CanonicalStateGate> CURRENT_WRITER = new ThreadLocal<>();
    // Gates for ChainState implementations that do not own one (test doubles). Weak keys: the gate
    // lives exactly as long as the chain state.
    private static final Map<ChainState, CanonicalStateGate> UNOWNED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ReentrantLock chainExtensionLock = new ReentrantLock();
    private final Supplier<ChainTip> tipReader;
    private final Set<CanonicalSnapshot> liveSnapshots = ConcurrentHashMap.newKeySet();
    private final AtomicLong shadowRefusals = new AtomicLong();
    private final AtomicLong overCapAcquisitions = new AtomicLong();
    // Live SHADOW_SYNC snapshots: bounded by shadow sync itself, so not counted against max-live-snapshots.
    private final AtomicInteger uncappedLive = new AtomicInteger();
    // Derived values shared by every snapshot of one generation (ADR-056 step 1d, M5): snapshots of the same
    // generation hold the same state, so a boundary dry run computed for one serves them all.
    private final ConcurrentHashMap<String, GenerationValue> generationMemo = new ConcurrentHashMap<>();
    private final AtomicLong generationMemoComputations = new AtomicLong();

    private record GenerationValue(long generation, Lookup<?> value) {
    }

    /** A derived value computation that may fail with a store error. */
    @FunctionalInterface
    interface Computation<T> {
        Lookup<T> compute() throws Exception;
    }

    private volatile CanonicalTip tip = CanonicalTip.UNKNOWN;
    private volatile LongToIntFunction slotToEpoch;
    private volatile IntToLongFunction epochStartSlot;
    private volatile IntSupplier completedBoundaryEpoch = () -> -1;
    private volatile CanonicalSnapshotSource snapshotSource;
    private volatile int maxLiveSnapshots = DEFAULT_MAX_LIVE_SNAPSHOTS;
    private final List<Consumer<CanonicalTip>> publicationListeners = new CopyOnWriteArrayList<>();

    // Owned by the thread holding the write lock.
    private boolean sectionChanged;
    private List<Runnable> afterRelease = new ArrayList<>();
    private CanonicalStateGate previousWriter;

    /**
     * @param tipReader reads the durable chain tip; called when a write section ends
     */
    public CanonicalStateGate(Supplier<ChainTip> tipReader) {
        this.tipReader = Objects.requireNonNull(tipReader, "tipReader");
    }

    /**
     * @return the gate of {@code chainState}: its own when it is a {@link CanonicalStateGateOwner},
     *         otherwise one gate per chain-state instance
     */
    public static CanonicalStateGate of(ChainState chainState) {
        Objects.requireNonNull(chainState, "chainState");
        if (chainState instanceof CanonicalStateGateOwner owner) {
            return owner.canonicalStateGate();
        }
        return UNOWNED.computeIfAbsent(chainState, cs -> {
            // The gate must not keep its weak key alive.
            WeakReference<ChainState> ref = new WeakReference<>(cs);
            return new CanonicalStateGate(() -> {
                ChainState state = ref.get();
                return state != null ? state.getTip() : null;
            });
        });
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Installs the slot-to-epoch function used for {@link CanonicalTip#epoch()} and republishes the
     * current tip (same generation) via {@link #refreshTip()}.
     */
    public void configureEpochCalculator(LongToIntFunction slotToEpoch) {
        this.slotToEpoch = slotToEpoch;
        refreshTip();
    }

    /**
     * Re-reads the durable chain tip and publishes it under the current generation. Used at startup,
     * before the first write section, so the published tip is not {@link CanonicalTip#UNKNOWN}. It
     * takes the write lock, so it never observes a block part-way through application.
     */
    public void refreshTip() {
        if (lock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("refreshTip must not run inside a canonical write section");
        }
        try (WriteSection section = enterWrite()) {
            section.markUnchanged();
            tip = readTip(tip.generation());
        }
    }

    /** Installs the epoch-to-first-slot function (the inverse of the epoch calculator). */
    public void configureEpochStartSlot(IntToLongFunction epochStartSlot) {
        this.epochStartSlot = epochStartSlot;
    }

    /** @return the first slot of {@code epoch}, or -1 when no function is configured or it fails */
    long epochStartSlot(int epoch) {
        IntToLongFunction fn = epochStartSlot;
        if (fn == null || epoch < 0) {
            return -1;
        }
        try {
            return fn.applyAsLong(epoch);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Installs the reader of the last <em>completed</em> epoch boundary (-1 when none), used for
     * {@link CanonicalTip#ledgerEpoch()}. It is read at the end of a write section and while
     * capturing, i.e. never while a writer is mid-block.
     */
    public void configureLedgerEpochReader(IntSupplier completedBoundaryEpoch) {
        this.completedBoundaryEpoch = completedBoundaryEpoch != null ? completedBoundaryEpoch : () -> -1;
    }

    /** Installs the source used to capture snapshots; {@code null} disables acquisition. */
    public void installSnapshotSource(CanonicalSnapshotSource source) {
        this.snapshotSource = source;
    }

    /** Sets the soft cap on live snapshots ({@code yano.validation.max-live-snapshots}). */
    public void setMaxLiveSnapshots(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max-live-snapshots must be >= 1: " + max);
        }
        this.maxLiveSnapshots = max;
    }

    public int maxLiveSnapshots() {
        return maxLiveSnapshots;
    }

    // ------------------------------------------------------------------ writers

    /**
     * Enters a canonical write section. Close the returned section in a {@code finally} block (or
     * try-with-resources). Reentrant.
     *
     * @throws IllegalStateException when the calling thread is capturing a snapshot (read lock held)
     */
    public WriteSection enterWrite() {
        if (lock.getReadHoldCount() > 0) {
            throw new IllegalStateException("Cannot enter a canonical write section while capturing a snapshot");
        }
        lock.writeLock().lock();
        if (lock.getWriteHoldCount() == 1) {
            sectionChanged = false;
            afterRelease = new ArrayList<>();
            previousWriter = CURRENT_WRITER.get();
            CURRENT_WRITER.set(this);
        }
        return new WriteSection();
    }

    /** Runs {@code body} in a write section. */
    public void runWrite(Runnable body) {
        Objects.requireNonNull(body, "body");
        try (WriteSection ignored = enterWrite()) {
            body.run();
        }
    }

    /** Runs {@code body} in a write section and returns its result. */
    public <T> T callWrite(Supplier<T> body) {
        Objects.requireNonNull(body, "body");
        try (WriteSection ignored = enterWrite()) {
            return body.get();
        }
    }

    /**
     * @return the lock that orders the upstream header store and a slot-leader producer when either extends
     *         the chain tip (see "Chain extension" above)
     */
    public Lock chainExtensionLock() {
        return chainExtensionLock;
    }

    /** @return true when the calling thread is inside a write section of this gate */
    public boolean isWriteHeldByCurrentThread() {
        return lock.isWriteLockedByCurrentThread();
    }

    /**
     * Runs {@code hook} after the calling thread's outermost write section releases the gate, or
     * immediately when the thread is not in a write section. Hooks run even when the section ended
     * with an exception, because the stores it wrote before failing stay committed. A hook failure
     * is logged and does not affect other hooks.
     */
    public static void runAfterWriteRelease(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        CanonicalStateGate writer = CURRENT_WRITER.get();
        if (writer != null && writer.lock.isWriteLockedByCurrentThread()) {
            writer.afterRelease.add(hook);
        } else {
            runHook(hook);
        }
    }

    /** @return the last published tip (a volatile read; never takes the gate) */
    public CanonicalTip tip() {
        return tip;
    }

    /** @return the last published generation */
    public long generation() {
        return tip.generation();
    }

    private void exitWrite(boolean changed) {
        if (!lock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Canonical write section closed by a thread that does not hold it");
        }
        if (changed) {
            sectionChanged = true;
        }
        if (lock.getWriteHoldCount() > 1) {
            lock.writeLock().unlock();
            return;
        }
        List<Runnable> hooks = afterRelease;
        afterRelease = new ArrayList<>();
        CanonicalTip published = null;
        try {
            if (sectionChanged) {
                publishNextGeneration();
                published = tip;
            }
        } finally {
            sectionChanged = false;
            if (previousWriter != null) {
                CURRENT_WRITER.set(previousWriter);
            } else {
                CURRENT_WRITER.remove();
            }
            previousWriter = null;
            lock.writeLock().unlock();
        }
        for (Runnable hook : hooks) {
            runHook(hook);
        }
        if (published != null) {
            CanonicalTip publishedTip = published;
            for (Consumer<CanonicalTip> listener : publicationListeners) {
                runHook(() -> listener.accept(publishedTip));
            }
        }
    }

    /**
     * Registers a listener called with the new tip after every write section that published a generation, once
     * the gate is released and the section's {@link #runAfterWriteRelease} hooks ran. It runs on the writer's
     * thread, so it must be short (the mempool only schedules its rebuild).
     */
    public void addPublicationListener(Consumer<CanonicalTip> listener) {
        publicationListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removePublicationListener(Consumer<CanonicalTip> listener) {
        publicationListeners.remove(listener);
    }

    /**
     * @return true when the calling thread holds the gate, as a writer or while capturing a snapshot (ADR-056 §6
     *         lock order: the mempool lane is never taken while this is true)
     */
    public boolean isHeldByCurrentThread() {
        return lock.isWriteLockedByCurrentThread() || lock.getReadHoldCount() > 0;
    }

    /**
     * {@code tip} with its epochs filled in when the epoch calculator became available after the tip was published
     * (its slot epoch was unknown then), exactly as snapshot acquisition computes them; the mempool's freshness
     * mark uses it too, so the two marks agree for one generation. Outside the read lock the boundary reader may
     * observe a writer mid-block; for the mempool that can only fail a freshness check (the rebuild restarts), never
     * pass a stale one, because the generation is compared as well.
     */
    public CanonicalTip withEpochs(CanonicalTip tip) {
        if (tip.tipSlotEpoch() >= 0 || tip.slot() < 0) {
            return tip;
        }
        int slotEpoch = epochOf(tip.slot());
        return new CanonicalTip(tip.generation(), tip.slot(), tip.blockHash(), slotEpoch, ledgerEpoch(slotEpoch));
    }

    /**
     * The slot mempool admission validates at for {@code tip} (the slot after it, or the first slot of the ledger
     * epoch in a producer's boundary window); pure, reads no state. See
     * {@link TickedLedgerView#admissionSlot(CanonicalSnapshot)}.
     */
    public long admissionSlot(CanonicalTip tip) {
        return TickedLedgerView.admissionSlot(tip, this::epochOf, this::epochStartSlot);
    }

    /** @return the epoch of {@link #admissionSlot(CanonicalTip)} (-1 when unknown) */
    public int admissionEpoch(CanonicalTip tip) {
        return epochOf(admissionSlot(tip));
    }

    private void publishNextGeneration() {
        tip = readTip(tip.generation() + 1);
    }

    private CanonicalTip readTip(long generation) {
        ChainTip chainTip;
        try {
            chainTip = tipReader.get();
        } catch (RuntimeException e) {
            log.warn("Could not read the chain tip for canonical generation {}: {}", generation, e.toString());
            chainTip = null;
        }
        if (chainTip == null || chainTip.getBlockHash() == null) {
            return new CanonicalTip(generation, -1, null, -1, ledgerEpoch(-1));
        }
        int slotEpoch = epochOf(chainTip.getSlot());
        return new CanonicalTip(generation, chainTip.getSlot(), HexUtil.encodeHexString(chainTip.getBlockHash()),
                slotEpoch, ledgerEpoch(slotEpoch));
    }

    private int ledgerEpoch(int slotEpoch) {
        int boundary;
        try {
            boundary = completedBoundaryEpoch.getAsInt();
        } catch (RuntimeException e) {
            log.warn("Could not read the last completed epoch boundary: {}", e.toString());
            boundary = -1;
        }
        return Math.max(slotEpoch, boundary);
    }

    /** @return the epoch of {@code slot}, or -1 when no epoch calculator is configured or it fails */
    int epochOf(long slot) {
        LongToIntFunction fn = slotToEpoch;
        if (fn == null || slot < 0) {
            return -1;
        }
        try {
            return fn.applyAsInt(slot);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void runHook(Runnable hook) {
        try {
            hook.run();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            log.error("Post-canonical-write hook failed: {}", t.toString(), t);
        }
    }

    /**
     * A canonical write section. Sections are changed by default; a section that turns out not to
     * have modified canonical state (for example a producer boundary check with no transition) calls
     * {@link #markUnchanged()} so it does not publish a new generation.
     */
    public final class WriteSection implements AutoCloseable {
        private boolean closed;
        private boolean unchanged;

        private WriteSection() {
        }

        /** Declares that this section did not modify canonical state. */
        public void markUnchanged() {
            unchanged = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            exitWrite(!unchanged);
        }
    }

    // ------------------------------------------------------------------ readers

    /**
     * Acquires a snapshot of the current canonical tip. The caller owns the returned reference and
     * must release it.
     *
     * @param purpose why the snapshot is needed; only {@link SnapshotPurpose#SHADOW} can be refused
     * @return the snapshot, or {@link Lookup.Unavailable} with the reason (never {@link Lookup.Absent})
     */
    public Lookup<CanonicalSnapshot> acquireSnapshot(SnapshotPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose");
        if (lock.isWriteLockedByCurrentThread()) {
            return Lookup.unavailable("canonical snapshot requested inside a canonical write section");
        }
        CanonicalSnapshotSource source = snapshotSource;
        if (source == null) {
            return Lookup.unavailable("canonical snapshots are not available (no snapshot source installed)");
        }
        // Checked before taking the read lock, so a source that can never produce a snapshot (async
        // UTxO apply) never waits behind, or delays, a writer.
        String unavailable = source.unavailableReason();
        if (unavailable != null) {
            return Lookup.unavailable("canonical snapshot unavailable: " + unavailable);
        }
        if (purpose == SnapshotPurpose.SHADOW_SYNC) {
            return Lookup.unavailable("SHADOW_SYNC snapshots are captured inside the block's write section "
                    + "(captureInWriteSection)");
        }
        int cap = maxLiveSnapshots;
        if (purpose == SnapshotPurpose.SHADOW && cappedLive() >= cap) {
            shadowRefusals.incrementAndGet();
            return Lookup.unavailable("live canonical snapshot cap reached (" + cap + "); shadow request dropped");
        }
        CanonicalSnapshot snapshot;
        lock.readLock().lock();
        try {
            CanonicalTip current = withEpochs(tip);
            CanonicalSnapshotSource.Captured captured = source.capture(current);
            if (captured == null) {
                return Lookup.unavailable("canonical snapshot source returned no state");
            }
            snapshot = new CanonicalSnapshot(this, current, purpose, captured);
            liveSnapshots.add(snapshot);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            return Lookup.unavailable("canonical snapshot unavailable: " + reason);
        } finally {
            lock.readLock().unlock();
        }
        int live = cappedLive();
        if (live > cap) {
            overCapAcquisitions.incrementAndGet();
            log.warn("Live canonical snapshots ({}) exceed the cap ({}) for a {} acquisition", live, cap, purpose);
        }
        return Lookup.present(snapshot);
    }

    /**
     * Admission check for a shadow task that will keep an <em>existing</em> snapshot alive (ADR-056 §7: a
     * frozen view retains the admission's snapshot rather than acquiring a new one). Such a task adds no
     * snapshot now but can keep a generation live after admission releases it, so it obeys the same cap as
     * {@link #acquireSnapshot(SnapshotPurpose) SHADOW acquisitions}: refused, and counted in
     * {@link #shadowRefusals()}, when the live snapshots reach {@code max-live-snapshots}.
     *
     * @return true when the shadow task may retain a snapshot
     */
    public boolean admitShadow() {
        if (cappedLive() >= maxLiveSnapshots) {
            shadowRefusals.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Invalidates every live snapshot: frees its RocksDB snapshot and makes its reads unavailable.
     * Must be called before the database the snapshots were taken on is closed or replaced.
     * Holders still release their references as usual.
     */
    public void invalidateSnapshots(String reason) {
        generationMemo.clear();
        for (CanonicalSnapshot snapshot : List.copyOf(liveSnapshots)) {
            snapshot.invalidate(reason);
        }
    }

    /**
     * Returns a derived value for {@code generation}, computing it at most once per generation and key. Only
     * Present results are kept; entries of other generations are evicted when a new one is stored.
     */
    @SuppressWarnings("unchecked")
    <T> Lookup<T> generationMemoized(long generation, String key, Computation<T> compute) throws Exception {
        GenerationValue cached = generationMemo.get(key);
        if (cached != null && cached.generation() == generation) {
            return (Lookup<T>) cached.value();
        }
        Lookup<T> computed = compute.compute();
        generationMemoComputations.incrementAndGet();
        if (computed.isPresent()) {
            generationMemo.values().removeIf(v -> v.generation() != generation);
            generationMemo.put(key, new GenerationValue(generation, computed));
        }
        return computed;
    }

    /** @return how many generation-scoped values were computed (not served from the memo) */
    public long generationMemoComputations() {
        return generationMemoComputations.get();
    }

    /**
     * Captures the state the calling writer has committed so far, from inside its own write section (ADR-056
     * Phase 7a, shadow sync). The follower applies a block's epoch boundary and then the block itself in one write
     * section, and publishes nothing in between; a hook at the start of the block's own application (the first
     * {@code BlockAppliedEvent} listener, after the boundary events) captures exactly the pre-block state: the
     * boundary applied, none of the block's changes.
     *
     * <ul>
     *   <li>Only the thread holding the write lock may call it (no other writer can interleave; readers wait), so
     *       no lock is taken and a writer cannot deadlock on itself.</li>
     *   <li>The snapshot's tip is the <em>published</em> tip (the parent block: its slot is the forecast basis), with
     *       its generation, but the ledger epoch is re-read now, so after a boundary it is the new epoch (and a
     *       ticked view over it is the canonical one).</li>
     *   <li>Its state is not the published generation's: it is marked {@link CanonicalSnapshot#publishedState()}
     *       false, and generation-scoped values are never shared with published snapshots.</li>
     *   <li>{@link SnapshotPurpose#SHADOW_SYNC} captures are never refused and do not count against
     *       {@code max-live-snapshots} (the caller bounds them); other purposes count as usual.</li>
     * </ul>
     *
     * @return the snapshot (the caller owns the first reference), or {@link Lookup.Unavailable}
     */
    public Lookup<CanonicalSnapshot> captureInWriteSection(SnapshotPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose");
        if (!lock.isWriteLockedByCurrentThread()) {
            return Lookup.unavailable("a pre-block capture is only possible inside the block's canonical write section");
        }
        CanonicalSnapshotSource source = snapshotSource;
        if (source == null) {
            return Lookup.unavailable("canonical snapshots are not available (no snapshot source installed)");
        }
        String unavailable = source.unavailableReason();
        if (unavailable != null) {
            return Lookup.unavailable("canonical snapshot unavailable: " + unavailable);
        }
        CanonicalTip published = withEpochs(tip);
        CanonicalTip preBlock = new CanonicalTip(published.generation(), published.slot(), published.blockHash(),
                published.tipSlotEpoch(), ledgerEpoch(published.tipSlotEpoch()));
        CanonicalSnapshot snapshot;
        try {
            CanonicalSnapshotSource.Captured captured = source.capture(preBlock);
            if (captured == null) {
                return Lookup.unavailable("canonical snapshot source returned no state");
            }
            snapshot = new CanonicalSnapshot(this, preBlock, purpose, captured, false);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            return Lookup.unavailable("canonical snapshot unavailable: " + reason);
        }
        if (purpose == SnapshotPurpose.SHADOW_SYNC) {
            uncappedLive.incrementAndGet();
        }
        liveSnapshots.add(snapshot);
        return Lookup.present(snapshot);
    }

    private int cappedLive() {
        return Math.max(0, liveSnapshots.size() - uncappedLive.get());
    }

    void onSnapshotFreed(CanonicalSnapshot snapshot) {
        if (liveSnapshots.remove(snapshot) && snapshot.purpose() == SnapshotPurpose.SHADOW_SYNC) {
            uncappedLive.decrementAndGet();
        }
    }

    /** @return snapshots with at least one live reference */
    public int liveSnapshotCount() {
        return liveSnapshots.size();
    }

    /** @return live snapshot count per canonical generation (a leak shows as a stale generation) */
    public Map<Long, Integer> liveSnapshotsByGeneration() {
        Map<Long, Integer> byGeneration = new TreeMap<>();
        for (CanonicalSnapshot snapshot : liveSnapshots) {
            byGeneration.merge(snapshot.generation(), 1, Integer::sum);
        }
        return byGeneration;
    }

    /** @return shadow acquisitions refused because of the cap ({@code yano_validation_shadow_dropped_total}) */
    public long shadowRefusals() {
        return shadowRefusals.get();
    }

    /** @return non-shadow acquisitions that exceeded the soft cap */
    public long overCapAcquisitions() {
        return overCapAcquisitions.get();
    }
}
