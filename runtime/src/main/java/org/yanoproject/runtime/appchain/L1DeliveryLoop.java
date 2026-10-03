package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.util.StoredBlockUtil;
import org.yanoproject.runtime.chain.BlockBodyRetentionRegistry;
import org.yanoproject.runtime.util.LifecycleFailures;
import org.rocksdb.WriteBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Delivers every canonical L1 block, and every L1 rollback, to the app chain in canonical order (app-layer ADR-038).
 * The node's chain state is the source of truth. Node events only wake the loop, and a periodic poll backs them up,
 * so a lost event costs latency, never correctness (I6). One durable {@link L1DeliveryRecord} holds the cursor and at
 * most one pending intent: a phase failure, a crash or a fork mid-attempt is always finished or undone before
 * anything else happens (D5a).
 */
final class L1DeliveryLoop implements AutoCloseable {
    private static final long POLL_MILLIS = 1_000;
    private static final long MAX_RETRY_DELAY_MILLIS = 30_000;
    private static final int MAX_BLOCKS_PER_PASS = 256;

    /** The app chain's forward and rollback phases (D3, D4a). Neither method may throw for a phase failure. */
    interface Host {
        List<L1PhaseResult> applyBlock(BlockAppliedEvent event);

        List<L1PhaseResult> rollbackTo(L1Point target);

        /** Whether the app ledger already holds L1-derived state, so a missing record means an upgrade (D8a). */
        boolean hasL1DerivedState();

        /** Judges every retained L1-derived record against chain state, read-only (D8b rules 1-6). */
        Reconciliation reconcile(BiPredicate<Long, byte[]> canonicalAtSlot);

        /** Called after a reconciliation commit so in-memory caches reread durable state. */
        void reconciled();
    }

    /**
     * The host's reconciliation decision (D8b).
     *
     * @param stager              stages the invalidations, or the quarantine markers, into the commit batch
     * @param quarantine          a terminal quarantine reason, or null
     * @param evidenceUnavailable needed evidence is missing: fail closed without applying anything
     * @param baselineTop         block number the baseline history must end at, or empty for the body tip
     */
    record Reconciliation(Consumer<WriteBatch> stager, String quarantine, boolean evidenceUnavailable,
                          OptionalLong baselineTop) {
        Reconciliation {
            Objects.requireNonNull(stager, "stager");
            Objects.requireNonNull(baselineTop, "baselineTop");
        }
    }

    enum State {
        STARTING, RUNNING, RETRYING_BLOCK, RETRYING_ROLLBACK,
        L1_BODY_UNAVAILABLE, L1_DIVERGENCE_BEYOND_WINDOW, L1_EVIDENCE_UNAVAILABLE, QUARANTINED
    }

    /** The immutable state readers see, replaced after each durable transition (D9a). */
    record Snapshot(L1DeliveryRecord record, State state, boolean reconciledSinceStart, String lastFailure) {
        L1Point cursor() {
            return record != null ? record.cursor() : null;
        }
    }

    private final String chainId;
    private final int depth;
    private final int capacity;
    private final ChainBlockReader reader;
    private final AppLedgerStore ledger;
    private final Host host;
    private final BlockBodyRetentionRegistry.Registration retention;
    private final Logger log;
    private final AtomicBoolean wakeQueued = new AtomicBoolean();
    private final AtomicBoolean rebaselineRequested = new AtomicBoolean();
    private volatile Snapshot snapshot = new Snapshot(null, State.STARTING, false, null);
    private volatile Consumer<Runnable> passRunner = Runnable::run;
    private volatile ScheduledExecutorService executor;
    private volatile boolean backingOff;
    private volatile boolean bypassBackoffOnce;
    private volatile long retryNotBeforeNanos;
    private long retryDelayMillis;

    L1DeliveryLoop(String chainId, int stabilityDepth, ChainBlockReader reader, AppLedgerStore ledger, Host host,
                   BlockBodyRetentionRegistry.Registration retention, Logger log) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.depth = Math.max(stabilityDepth, 0);
        this.capacity = Math.max(stabilityDepth, 1) + 64;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.host = Objects.requireNonNull(host, "host");
        this.retention = retention;
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Starts the loop thread. Each pass runs through {@code passRunner}, the subsystem's generation lease. */
    void start(Consumer<Runnable> passRunner) {
        this.passRunner = Objects.requireNonNull(passRunner, "passRunner");
        ScheduledExecutorService loopExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "app-chain-l1-delivery-" + chainId);
            thread.setDaemon(true);
            return thread;
        });
        this.executor = loopExecutor;
        // The poll only wakes, so a pass that escapes with a process-fatal error cannot cancel it (I6).
        loopExecutor.scheduleWithFixedDelay(this::wake, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Coalesced wake-up from a node rollback event: it may have killed the intent being retried, so this wake skips
     * a retry backoff once and the rollback is derived promptly (D6).
     */
    void wakeForRollback() {
        bypassBackoffOnce = true;
        wake();
    }

    /** Coalesced wake-up from a node event or the poll; safe on the publishing thread. Respects a retry backoff. */
    void wake() {
        ScheduledExecutorService loopExecutor = executor;
        if (loopExecutor != null && wakeQueued.compareAndSet(false, true)) {
            try {
                loopExecutor.execute(this::runPass);
            } catch (RejectedExecutionException closed) {
                wakeQueued.set(false);
            }
        }
    }

    /**
     * Requests an operator re-baseline (D7a): the next pass reconciles every retained L1-derived record (D8b) and
     * records a fresh baseline history at the body tip. Refused while a terminal quarantine is persisted.
     *
     * @return false when refused
     */
    boolean requestRebaseline() {
        L1DeliveryRecord record = snapshot.record();
        if (record != null && record.quarantined()) {
            return false;
        }
        rebaselineRequested.set(true);
        bypassBackoffOnce = true;
        wake();
        return true;
    }

    /**
     * Stops scheduling passes without waiting. A pass already running holds the subsystem's generation lease, and
     * the ledger closes only after that lease drains, so nothing touches a closed ledger.
     */
    @Override
    public void close() {
        ScheduledExecutorService loopExecutor = executor;
        executor = null;
        if (loopExecutor != null) {
            loopExecutor.shutdown();
        }
    }

    Snapshot snapshot() {
        return snapshot;
    }

    // ------------------------------------------------------------------
    // Readers (D9a): one published snapshot, plus an at-read canonical check of the effective cursor.
    // ------------------------------------------------------------------

    /** Delivery health for the voting gate: clean state and a canonical effective cursor. */
    boolean deliveryHealthy() {
        Snapshot current = snapshot;
        return clean(current) && canonical(current.cursor());
    }

    /** The stable L1 point (depth below the newest delivered point), or null when unavailable. */
    AppChainEngine.L1Ref stablePoint() {
        Snapshot current = snapshot;
        if (depth <= 0 || !clean(current)) {
            return null;
        }
        List<L1Point> window = current.record().window();
        if (window.size() < depth + 1 || !canonical(current.cursor())) {
            return null;
        }
        return window.get(window.size() - 1 - depth).toRef();
    }

    /**
     * The effective cursor's slot when delivery is healthy, else -1. L1 facts recorded outside the loop must sit at
     * or below it, so that the loop's rollback, which starts from recorded points, always covers them (D3).
     */
    long healthyCursorSlot() {
        Snapshot current = snapshot;
        return clean(current) && canonical(current.cursor()) ? current.cursor().slot() : -1L;
    }

    /** Follower verdict on a proposed L1 reference against the delivered window (ADR 008.1 I1.3). */
    AppChainEngine.L1RefVerdict checkL1Ref(long slot, byte[] blockHash) {
        Snapshot current = snapshot;
        if (!clean(current) || !canonical(current.cursor())) {
            return AppChainEngine.L1RefVerdict.UNKNOWN;
        }
        List<L1Point> window = current.record().window();
        if (window.isEmpty()) {
            return AppChainEngine.L1RefVerdict.UNKNOWN;
        }
        if (slot > window.getLast().slot()) {
            return AppChainEngine.L1RefVerdict.AHEAD;
        }
        if (slot < window.getFirst().slot()) {
            return AppChainEngine.L1RefVerdict.UNKNOWN;
        }
        for (int fromEnd = 0; fromEnd < window.size(); fromEnd++) {
            L1Point point = window.get(window.size() - 1 - fromEnd);
            if (point.slot() == slot) {
                if (!Arrays.equals(point.blockHash(), blockHash)) {
                    return AppChainEngine.L1RefVerdict.MISMATCH;
                }
                return fromEnd < depth ? AppChainEngine.L1RefVerdict.AHEAD : AppChainEngine.L1RefVerdict.OK;
            }
        }
        return AppChainEngine.L1RefVerdict.MISMATCH;
    }

    /**
     * D9a checks 1 and 2. An APPLY attempt in progress keeps the fence open: readers see only the committed window,
     * which the attempt cannot change until its commit. A pending ROLLBACK, a retry or a failure closes it.
     */
    private static boolean clean(Snapshot current) {
        L1DeliveryRecord record = current.record();
        return record != null && current.state() == State.RUNNING && current.reconciledSinceStart()
                && record.phase() == L1DeliveryRecord.Phase.RECONCILED
                && (record.pending() == null || record.pending().kind() == L1DeliveryRecord.Intent.Kind.APPLY);
    }

    private boolean canonical(L1Point point) {
        if (point == null) {
            return false;
        }
        if (point.isOrigin()) {
            return true;
        }
        return reader.getCanonicalBlockReference(point.blockNumber()).map(point::matches).orElse(false);
    }

    // ------------------------------------------------------------------
    // The loop (ADR §7)
    // ------------------------------------------------------------------

    /** One scheduled pass: the retry backoff, the generation lease and failure containment around {@link #pass}. */
    // Package-private so tests can drive the scheduled path, backoff included.
    void runPass() {
        wakeQueued.set(false);
        boolean bypassBackoff = bypassBackoffOnce;
        bypassBackoffOnce = false;
        if (!bypassBackoff && backingOff && System.nanoTime() - retryNotBeforeNanos < 0) {
            return;
        }
        try {
            passRunner.accept(this::pass);
            backOffWhileRetrying();
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatal(failure);
            Snapshot current = snapshot;
            State retrying = current.record() != null && current.record().pending() != null
                    && current.record().pending().kind() == L1DeliveryRecord.Intent.Kind.ROLLBACK
                    ? State.RETRYING_ROLLBACK : State.RETRYING_BLOCK;
            snapshot = new Snapshot(current.record(), retrying, current.reconciledSinceStart(),
                    failure.getClass().getName());
            log.warn("App-chain '{}' L1 delivery pass failed (errorType={})", chainId,
                    failure.getClass().getName());
            backOffWhileRetrying();
        }
    }

    /** Bounded exponential backoff while the same intent keeps failing (D4, section 7); reset by any success. */
    private void backOffWhileRetrying() {
        State state = snapshot.state();
        if (state == State.RETRYING_BLOCK || state == State.RETRYING_ROLLBACK) {
            retryDelayMillis = Math.min(Math.max(retryDelayMillis * 2, POLL_MILLIS), MAX_RETRY_DELAY_MILLIS);
            retryNotBeforeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryDelayMillis);
            backingOff = true;
        } else {
            retryDelayMillis = 0;
            backingOff = false;
        }
    }

    // Package-private so tests can drive one deterministic pass.
    void pass() {
        L1DeliveryRecord record = snapshot.record();
        if (record == null) {
            record = load();
            if (record == null) {
                return;
            }
        }
        if (rebaselineRequested.getAndSet(false)) {
            if (record.quarantined()) {
                log.warn("App-chain '{}' L1 re-baseline refused: a terminal quarantine is persisted", chainId);
            } else {
                record = persist(new L1DeliveryRecord(record.baseline(), List.of(), null,
                        L1DeliveryRecord.Phase.RECONCILING, null));
                log.warn("App-chain '{}' L1 re-baseline started by operator", chainId);
            }
        }
        if (record.terminal() != null) {
            publish(record, State.valueOf(record.terminal().state()), false, record.terminal().reason());
            return;
        }
        if (record.phase() == L1DeliveryRecord.Phase.RECONCILING) {
            reconcilePass(record);
            return;
        }

        // Pre-attempt check: the cursor, and any pending APPLY, must still be canonical (D5a, D6).
        L1DeliveryRecord.Intent pending = record.pending();
        if (pending == null || pending.kind() == L1DeliveryRecord.Intent.Kind.APPLY) {
            boolean applyCanonical = pending == null || canonical(pending.point());
            if (!canonical(record.cursor()) || !applyCanonical) {
                L1Point target = newestCanonicalRecorded(record);
                if (target == null) {
                    fail(record, State.L1_DIVERGENCE_BEYOND_WINDOW, "NO_RECORDED_POINT_IS_CANONICAL");
                    return;
                }
                updateRetention(target);
                record = persist(record.withPending(
                        new L1DeliveryRecord.Intent(L1DeliveryRecord.Intent.Kind.ROLLBACK, target)));
            }
        }
        if (record.pending() != null && record.pending().kind() == L1DeliveryRecord.Intent.Kind.ROLLBACK) {
            L1Point target = record.pending().point();
            L1PhaseResult failure = firstFailure(rollbackPhases(target));
            if (failure != null) {
                if (failure.kind() == L1PhaseResult.Kind.QUARANTINED) {
                    fail(record, State.QUARANTINED, failure.reason());
                } else {
                    publish(record, State.RETRYING_ROLLBACK, false, failure.reason());
                }
                return;
            }
            record = persist(record.rolledBackTo(target));
            updateRetention(record.cursor());
            log.info("App-chain '{}' L1 delivery rolled back to {}", chainId, target);
        }
        publish(record, State.RUNNING, true, null);

        // Forward delivery, bound to the pending intent (D1, D4, D5, D5a; I12).
        for (int delivered = 0; delivered < MAX_BLOCKS_PER_PASS; delivered++) {
            if (record.pending() == null) {
                ChainTip tip = reader.getLocalTip();
                if (tip == null || record.cursor().blockNumber() >= tip.getBlockNumber()) {
                    return;
                }
                // The reference first, then the cursor (section 7): a fork landing between the two reads makes
                // the cursor check fail, so a new-branch block is never attempted on a dead cursor (I2).
                Optional<CanonicalBlockReference> next =
                        reader.getCanonicalBlockReference(record.cursor().blockNumber() + 1);
                if (!canonical(record.cursor())) {
                    wake(); // the pre-attempt check rolls back promptly
                    return;
                }
                if (next.isEmpty()) {
                    return; // index not written yet; the next poll retries
                }
                record = persist(record.withPending(new L1DeliveryRecord.Intent(
                        L1DeliveryRecord.Intent.Kind.APPLY, L1Point.of(next.get()))));
                publish(record, State.RUNNING, true, null);
            }
            L1Point point = record.pending().point();
            BlockRead read = readBlock(point);
            if (read.event() == null) {
                switch (read.problem()) {
                    case CHANGED -> wake();
                    case PRUNED -> fail(record, State.L1_BODY_UNAVAILABLE, "L1_BODY_PRUNED_AT_" + point.blockNumber());
                    default -> publish(record, State.RETRYING_BLOCK, true, read.problem().name());
                }
                return;
            }
            L1PhaseResult failure = firstFailure(applyPhases(read.event()));
            if (failure != null) {
                if (failure.kind() == L1PhaseResult.Kind.QUARANTINED) {
                    fail(record, State.QUARANTINED, failure.reason());
                } else {
                    publish(record, State.RETRYING_BLOCK, true, failure.reason());
                }
                return;
            }
            if (!canonical(point) || !canonical(record.cursor())) {
                // A fork landed during the attempt; the pre-attempt check turns the intent into a rollback.
                wake();
                return;
            }
            record = persist(record.committed(point, capacity));
            updateRetention(point);
            publish(record, State.RUNNING, true, null);
        }
        wake();
    }

    private L1DeliveryRecord load() {
        byte[] encoded = ledger.metaBytes(L1DeliveryRecord.META_KEY);
        if (encoded != null && encoded.length > 0) {
            L1DeliveryRecord record = L1DeliveryRecord.decode(encoded);
            updateRetention(record.pending() != null
                    && record.pending().kind() == L1DeliveryRecord.Intent.Kind.ROLLBACK
                    ? record.pending().point() : record.cursor());
            publish(record, State.STARTING, false, null);
            return record;
        }
        if (host.hasL1DerivedState()) {
            // An upgrade: legacy L1-derived state is reconciled before any delivery (D8a, D8b rule 8).
            L1DeliveryRecord record = persist(L1DeliveryRecord.fresh(List.of(L1Point.ORIGIN),
                    L1DeliveryRecord.Phase.RECONCILING));
            publish(record, State.STARTING, false, null);
            log.warn("App-chain '{}' reconciles existing L1-derived state before delivering", chainId);
            return record;
        }
        List<L1Point> baseline = collectBaseline();
        if (baseline == null) {
            return null;
        }
        L1DeliveryRecord record = persist(L1DeliveryRecord.fresh(baseline, L1DeliveryRecord.Phase.RECONCILED));
        updateRetention(record.cursor());
        publish(record, State.STARTING, false, null);
        log.info("App-chain '{}' L1 delivery starts after {}", chainId, record.cursor());
        return record;
    }

    /**
     * Collects the baseline history as one coherent chain history (D8; D8b rule 7): the last {@code capacity}
     * canonical points ending at the body tip, read while the canonical mutation sequence stays even and unchanged.
     * Returns null to retry later.
     */
    List<L1Point> collectBaseline() {
        OptionalLong before = reader.canonicalMutationSequence();
        if (before.isEmpty()) {
            publish(null, State.L1_EVIDENCE_UNAVAILABLE, false, "CANONICAL_MUTATION_SEQUENCE_UNSUPPORTED");
            return null;
        }
        if ((before.getAsLong() & 1L) != 0) {
            return null;
        }
        List<L1Point> points = readBaseline(OptionalLong.empty());
        OptionalLong after = reader.canonicalMutationSequence();
        return points != null && after.isPresent() && after.getAsLong() == before.getAsLong() ? points : null;
    }

    /** The last {@code capacity} canonical points ending at {@code top} (default the body tip); null on a gap. */
    private List<L1Point> readBaseline(OptionalLong top) {
        ChainTip tip = reader.getLocalTip();
        long newest = top.isPresent() ? top.getAsLong() : tip == null ? -1L : tip.getBlockNumber();
        List<L1Point> points = new ArrayList<>();
        if (newest < 0) {
            points.add(L1Point.ORIGIN);
            return points;
        }
        long bottom = Math.max(0L, newest - capacity + 1);
        if (bottom == 0L) {
            points.add(L1Point.ORIGIN);
        }
        for (long blockNumber = bottom; blockNumber <= newest; blockNumber++) {
            Optional<CanonicalBlockReference> reference = reader.getCanonicalBlockReference(blockNumber);
            if (reference.isEmpty()) {
                return null;
            }
            points.add(L1Point.of(reference.get()));
        }
        return points;
    }

    /**
     * One reconciliation pass (D8b rules 7-9): the host decides read-only, the baseline history is collected from
     * the same chain, and both are committed in one batch only if the canonical mutation sequence stayed even and
     * unchanged. Otherwise everything is discarded and the next pass reruns.
     */
    private void reconcilePass(L1DeliveryRecord record) {
        OptionalLong before = reader.canonicalMutationSequence();
        if (before.isEmpty()) {
            publish(record, State.L1_EVIDENCE_UNAVAILABLE, false, "CANONICAL_MUTATION_SEQUENCE_UNSUPPORTED");
            return;
        }
        publish(record, State.STARTING, false, null);
        if ((before.getAsLong() & 1L) != 0) {
            return;
        }
        Reconciliation decision = host.reconcile(this::canonicalAtSlot);
        List<L1Point> baseline = readBaseline(decision.baselineTop());
        OptionalLong after = reader.canonicalMutationSequence();
        if (baseline == null || after.isEmpty() || after.getAsLong() != before.getAsLong()) {
            return;
        }
        L1DeliveryRecord next;
        Consumer<WriteBatch> stager;
        if (decision.evidenceUnavailable()) {
            next = record.withTerminal(new L1DeliveryRecord.Terminal(State.L1_EVIDENCE_UNAVAILABLE.name(),
                    "L1_EVIDENCE_UNAVAILABLE"));
            stager = batch -> { };
        } else if (decision.quarantine() != null) {
            next = record.withTerminal(new L1DeliveryRecord.Terminal(State.QUARANTINED.name(),
                    decision.quarantine()));
            stager = decision.stager();
        } else {
            next = L1DeliveryRecord.fresh(baseline, L1DeliveryRecord.Phase.RECONCILED);
            stager = decision.stager();
        }
        L1DeliveryRecord committed = next;
        ledger.writeAtomically(batch -> {
            stager.accept(batch);
            ledger.stageMetaBytes(batch, L1DeliveryRecord.META_KEY, committed.encode());
        });
        host.reconciled();
        if (committed.terminal() != null) {
            publish(committed, State.valueOf(committed.terminal().state()), false, committed.terminal().reason());
            log.error("App-chain '{}' L1 reconciliation stopped: {}; operator action required", chainId,
                    committed.terminal().reason());
            return;
        }
        updateRetention(committed.cursor());
        publish(committed, State.STARTING, false, null);
        log.info("App-chain '{}' L1 reconciliation complete; delivery starts after {}", chainId,
                committed.cursor());
        wake();
    }

    private boolean canonicalAtSlot(long slot, byte[] blockHash) {
        return reader.getCanonicalBlockReferenceAtSlot(slot)
                .map(reference -> Arrays.equals(reference.blockHash(), blockHash)).orElse(false);
    }

    /** The newest recorded point that is still canonical: the window, then the baseline history (I16). */
    private L1Point newestCanonicalRecorded(L1DeliveryRecord record) {
        for (List<L1Point> points : List.of(record.window(), record.baseline())) {
            for (int index = points.size() - 1; index >= 0; index--) {
                if (canonical(points.get(index))) {
                    return points.get(index);
                }
            }
        }
        return null;
    }

    private List<L1PhaseResult> applyPhases(BlockAppliedEvent event) {
        try {
            return host.applyBlock(event);
        } catch (RuntimeException failure) {
            return List.of(L1PhaseResult.retryable("L1_PHASE_THREW_" + failure.getClass().getSimpleName()));
        }
    }

    private List<L1PhaseResult> rollbackPhases(L1Point target) {
        try {
            return host.rollbackTo(target);
        } catch (RuntimeException failure) {
            return List.of(L1PhaseResult.retryable("L1_ROLLBACK_THREW_" + failure.getClass().getSimpleName()));
        }
    }

    private static L1PhaseResult firstFailure(List<L1PhaseResult> results) {
        L1PhaseResult retryable = null;
        for (L1PhaseResult result : results) {
            if (result.kind() == L1PhaseResult.Kind.QUARANTINED) {
                return result;
            }
            if (retryable == null && !result.succeeded()) {
                retryable = result;
            }
        }
        return retryable;
    }

    private enum ReadProblem { CHANGED, PRUNED, UNAVAILABLE, MALFORMED }

    private record BlockRead(BlockAppliedEvent event, ReadProblem problem) {
    }

    /** Reads the body of exactly {@code point}; any other block at that number is reported, never substituted. */
    private BlockRead readBlock(L1Point point) {
        byte[] body = reader.getBlockByNumber(point.blockNumber());
        if (body == null) {
            OptionalLong earliest = reader.getEarliestRetainedBodyBlockNumber();
            boolean pruned = earliest.isPresent() && point.blockNumber() < earliest.getAsLong();
            return new BlockRead(null, pruned ? ReadProblem.PRUNED : ReadProblem.UNAVAILABLE);
        }
        String hashHex = HexUtil.encodeHexString(point.blockHash());
        Era storedEra = reader.getBlockEra(point.blockNumber());
        if (StoredBlockUtil.isStoredByronBlock(storedEra, body)) {
            // Byron blocks reach the phases without a parsed body, as the node's own Byron events do.
            return canonical(point)
                    ? new BlockRead(new BlockAppliedEvent(Era.Byron, point.slot(), point.blockNumber(), hashHex,
                    null), null)
                    : new BlockRead(null, ReadProblem.CHANGED);
        }
        Block block;
        try {
            block = BlockSerializer.INSTANCE.deserialize(body);
        } catch (RuntimeException malformed) {
            return canonical(point) ? new BlockRead(null, ReadProblem.MALFORMED)
                    : new BlockRead(null, ReadProblem.CHANGED);
        }
        HeaderBody header = block.getHeader() != null ? block.getHeader().getHeaderBody() : null;
        if (header == null || header.getSlot() != point.slot() || header.getBlockNumber() != point.blockNumber()
                || !hashHex.equals(header.getBlockHash())) {
            return canonical(point) ? new BlockRead(null, ReadProblem.MALFORMED)
                    : new BlockRead(null, ReadProblem.CHANGED);
        }
        Era era = block.getEra() != null ? block.getEra() : storedEra;
        return new BlockRead(new BlockAppliedEvent(era, point.slot(), point.blockNumber(), hashHex, block), null);
    }

    private L1DeliveryRecord persist(L1DeliveryRecord record) {
        ledger.metaPutAll(Map.of(), Map.of(L1DeliveryRecord.META_KEY, record.encode()));
        return record;
    }

    private void fail(L1DeliveryRecord record, State state, String reason) {
        L1DeliveryRecord failed = persist(record.withTerminal(new L1DeliveryRecord.Terminal(state.name(), reason)));
        publish(failed, state, false, reason);
        log.error("App-chain '{}' L1 delivery stopped: {} ({}); operator re-baseline required", chainId, state,
                reason);
    }

    private void publish(L1DeliveryRecord record, State state, boolean reconciled, String failure) {
        snapshot = new Snapshot(record, state, reconciled, failure);
    }

    /** The chain requires bodies after {@code point}; ORIGIN keeps everything (D7). */
    private void updateRetention(L1Point point) {
        if (retention != null) {
            retention.update(OptionalLong.of(point.blockNumber() + 1));
        }
    }
}
