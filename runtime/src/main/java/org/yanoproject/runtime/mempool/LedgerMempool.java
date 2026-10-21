package org.yanoproject.runtime.mempool;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import lombok.extern.slf4j.Slf4j;
import org.yanoproject.api.model.MemPoolTransaction;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.ValidationError;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.runtime.chain.MemPool;
import org.yanoproject.runtime.chain.MempoolAdmissionException;
import org.yanoproject.runtime.chain.MempoolAdmissionLimits;
import org.yanoproject.runtime.chain.MempoolAdmissionResult;
import org.yanoproject.runtime.chain.MempoolStats;
import org.yanoproject.runtime.validation.LedgerAdmissionScope;
import org.yanoproject.runtime.validation.UtxoConversions;
import org.yanoproject.runtime.validation.ValidationEnvFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The mempool over ledger-state overlays (ADR-056 §6), used when an engine-API admission engine is configured
 * ({@code yano.validation.engine} other than {@code scalus}). The legacy {@code scalus} keeps {@code DefaultMemPool}.
 *
 * <h2>State</h2>
 * <p>One immutable {@link MempoolLedgerState} is published through a volatile reference. Queries read it without
 * a lock. Every mutation (admission, removal, eviction, clear, TTL, a rebuild's swap) runs under the fair mutation
 * lane, builds a new state and swaps it in.</p>
 *
 * <h2>Lock order</h2>
 * <p>The canonical gate and the lane are never held together: bases (canonical snapshots) are acquired only while
 * the lane is not held; the lane is never taken while the gate is held; freshness checks under the lane read the
 * published canonical mark ({@link MempoolBaseSource#current()}, a volatile read). Both directions are asserted and
 * counted ({@link LedgerMempoolStatus#lockOrderViolations()}).</p>
 *
 * <h2>Admission</h2>
 * <p>Under the lane: duplicate, conflict and capacity checks against the published state; then the validation
 * callback (the node's validation event, whose default listener runs rule {@code MEMPOOL} through
 * {@link LedgerAdmissionScope}) against the published overlay; on success the {@code ValidatedTx} and its effects
 * layer are appended and swapped in. The admission retains the published base for its duration.</p>
 *
 * <h2>Removal, eviction, clear</h2>
 * <p>Synchronous truncate-and-reapply under the lane: the overlay is truncated to the layer before the earliest
 * removed transaction and the remaining suffix is re-applied in order (re-application when {@code previous} is
 * reusable, {@link org.yanoproject.ledger.rules.conway.ReapplyPolicy}); a transaction that now fails is dropped, so
 * certificate and governance dependents cascade like UTxO dependents.</p>
 *
 * <h2>Rebuild on a canonical change</h2>
 * <p>One worker, coalesced triggers (canonical publications, a stale mark seen by an admission), steps 1–8 of §6:
 * {@link #rebuildNow()}.</p>
 */
@Slf4j
public final class LedgerMempool implements MemPool, AutoCloseable {

    private static final long SLOW_VALIDATION_NANOS = 1_000_000_000L;
    private static final long CLOSE_WAIT_SECONDS = 10;

    /** Whether the mempool admits transactions. */
    public enum Status {
        /** The published state is being maintained; admission is open (possibly provisional, see the lag contract). */
        READY,
        /** Rebuilds could not catch up with the canonical chain: admission is retryable-rejected, selection skips. */
        CATCHING_UP
    }

    /**
     * @param maxRestarts       {@code yano.validation.rebuild-max-restarts}: off-lane attempts before the
     *                          synchronous fallback (default 3)
     * @param syncAttempts      {@code yano.validation.rebuild-sync-attempts}: synchronous attempts before
     *                          {@link Status#CATCHING_UP} (default 3)
     * @param catchingUpRetryMs retry interval while catching up without new canonical publications
     */
    public record Settings(int maxRestarts, int syncAttempts, long catchingUpRetryMs) {
        public Settings {
            if (maxRestarts < 0 || syncAttempts < 1 || catchingUpRetryMs <= 0) {
                throw new IllegalArgumentException("invalid mempool rebuild settings");
            }
        }

        public static Settings defaults() {
            return new Settings(3, 3, 1_000);
        }
    }

    /** Observes the rebuild's steps (diagnostics and the Phase 6 gate tests). Callbacks must not block for long. */
    public interface RebuildObserver {
        /** Step 3 (or the synchronous fallback's fold) is about to run against {@code target}. */
        default void foldStarted(CanonicalMark target, int transactions, boolean synchronous) {
        }

        default void foldCompleted(CanonicalMark target, int kept, int dropped) {
        }

        /** Step 4.1, under the lane: the transactions appended since step 2 are about to be validated on top. */
        default void reconcilingAppends(CanonicalMark target, List<String> txHashes) {
        }

        default void discarded(CanonicalMark target, String reason) {
        }

        default void published(CanonicalMark target, long mempoolGeneration) {
        }

        /** Every re-validation of a mempool transaction (rebuild fold, append reconciliation, removal suffix). */
        default void revalidated(String txHash, TxValidationRequest request, TxValidationOutcome outcome) {
        }

        default void statusChanged(Status status) {
        }

        /** Every candidate validation of a block selection (rule {@code LEDGER}, origin {@code BLOCK_BUILD}). */
        default void blockCandidateValidated(String txHash, TxValidationRequest request, TxValidationOutcome outcome) {
        }
    }

    /**
     * The result of {@link #selectForBlock(long)}.
     *
     * @param transactions the selected transactions, in mempool order, each valid on top of the previous ones
     * @param generation   the canonical generation they were validated against (-1 when nothing was validated)
     * @param forgeSlot    the slot they were validated for (-1 when nothing was validated)
     * @param rejected     candidates that failed a ledger rule (removed through {@link #removeInvalidated})
     * @param skipped      candidates skipped without removal (a transient failure, or after one)
     * @param reapplied    selected candidates that were re-applied (static checks and Plutus skipped)
     * @param transientFailures the skipped candidates whose own validation failed transiently (not only skipped
     *                     after an earlier one), with the failure; each counts towards the transient-skip limit
     */
    public record BlockSelection(List<byte[]> transactions, long generation, long forgeSlot, List<String> rejected,
                                 List<String> skipped, int reapplied, Map<String, String> transientFailures) {
        public BlockSelection {
            transactions = List.copyOf(transactions);
            rejected = List.copyOf(rejected);
            skipped = List.copyOf(skipped);
            transientFailures = Map.copyOf(transientFailures);
        }

        static BlockSelection empty() {
            return new BlockSelection(List.of(), -1, -1, List.of(), List.of(), 0, Map.of());
        }
    }

    private enum Attempt { PUBLISHED, DISCARDED }

    private final LedgerValidationEngine engine;
    private final ValidationEnvFactory envFactory;
    private final MempoolBaseSource source;
    private final Executor rebuildExecutor;
    private final ScheduledExecutorService retryScheduler;
    private final Settings settings;
    private volatile RebuildObserver observer = new RebuildObserver() { };

    private final ReentrantLock lane = new ReentrantLock(true);
    private final ReentrantLock rebuildLock = new ReentrantLock();
    private volatile MempoolLedgerState published;
    private volatile Status status = Status.READY;
    private volatile boolean rebuildInProgress;
    private volatile boolean closed;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean workerScheduled = new AtomicBoolean();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicLong cursor = new AtomicLong();
    private AutoCloseable publicationHandle;

    // Mutated under the lane.
    private long mutationSeq;
    private long mempoolGeneration;
    private long duplicateRejections;
    private long conflictRejections;
    private long ledgerRejections;
    private long cascadedRemovals;
    private long totalAdmissionWaitNanos;
    private long totalAdmissionHoldNanos;
    private long totalValidationNanos;
    private long slowValidations;

    private final AtomicLong capacityRejections = new AtomicLong();
    private final AtomicLong malformedRejections = new AtomicLong();
    private final AtomicLong catchingUpRejections = new AtomicLong();
    private final AtomicLong rebuildsPublished = new AtomicLong();
    private final AtomicLong rebuildsDiscarded = new AtomicLong();
    private final AtomicLong synchronousFallbacks = new AtomicLong();
    private final AtomicLong catchingUpEntered = new AtomicLong();
    private final AtomicLong reapplications = new AtomicLong();
    private final AtomicLong fullRevalidations = new AtomicLong();
    private final AtomicLong revalidationDrops = new AtomicLong();
    private final AtomicLong deferredRemovals = new AtomicLong();
    private final AtomicLong lockOrderViolations = new AtomicLong();
    private volatile long lastRebuildMillis;
    private final AtomicLong blockSelections = new AtomicLong();
    private final AtomicLong blockSelectionRedos = new AtomicLong();
    private final AtomicLong blockSelectionReapplications = new AtomicLong();
    private final AtomicLong blockSelectionFullValidations = new AtomicLong();
    private final AtomicLong blockSelectionRejected = new AtomicLong();
    private final AtomicLong blockSelectionSkipped = new AtomicLong();
    private volatile long lastBlockSelectionMillis;

    /**
     * @param engine          the admission engine (rule {@code MEMPOOL} for admission and rebuilds)
     * @param envFactory      builds the validation environment of a base
     * @param source          canonical bases
     * @param rebuildExecutor runs the single rebuild worker
     * @param retryScheduler  schedules retries while catching up, or {@code null} (retries then wait for the next
     *                        canonical publication)
     */
    public LedgerMempool(LedgerValidationEngine engine, ValidationEnvFactory envFactory, MempoolBaseSource source,
                         Executor rebuildExecutor, ScheduledExecutorService retryScheduler, Settings settings) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.envFactory = Objects.requireNonNull(envFactory, "envFactory");
        this.source = Objects.requireNonNull(source, "source");
        this.rebuildExecutor = Objects.requireNonNull(rebuildExecutor, "rebuildExecutor");
        this.retryScheduler = retryScheduler;
        this.settings = settings != null ? settings : Settings.defaults();
    }

    /**
     * Registers for canonical publications and publishes the initial (empty) state over the current canonical base,
     * acquired on the calling thread outside the lane.
     */
    public synchronized void start() {
        if (publicationHandle == null) {
            publicationHandle = source.onPublication(mark -> requestRebuild("canonical publication"));
        }
        ensurePublished();
    }

    public void setObserver(RebuildObserver observer) {
        this.observer = observer != null ? observer : new RebuildObserver() { };
    }

    public Status status() {
        return status;
    }

    /** @return true when the published state's base is behind the published canonical mark */
    public boolean isStale() {
        MempoolLedgerState s = published;
        return s == null || !s.mark().equals(source.current());
    }

    // ================================================================== admission

    @Override
    public MempoolAdmissionResult tryAdmit(byte[] txBytes, Function<Outpoint, Utxo> canonicalResolver,
                                           AdmissionValidator validator, MempoolAdmissionLimits limits,
                                           Consumer<MemPoolTransaction> acceptedListener) {
        // Canonical UTxOs come from the published state's own snapshot, never from a live resolver (§6).
        return tryAdmit(txBytes, TxValidationRequest.Origin.LOCAL, validator, limits, acceptedListener);
    }

    /**
     * Admits one transaction (ADR-056 §6, "Admission").
     *
     * @param origin    {@code LOCAL} or {@code PEER}
     * @param validator the validation callback (the node's validation event); {@code null} runs the engine only
     */
    public MempoolAdmissionResult tryAdmit(byte[] txBytes, TxValidationRequest.Origin origin,
                                           AdmissionValidator validator, MempoolAdmissionLimits limits,
                                           Consumer<MemPoolTransaction> acceptedListener) {
        Objects.requireNonNull(txBytes, "txBytes");
        Objects.requireNonNull(origin, "origin");
        if (lane.isHeldByCurrentThread()) {
            return result(MempoolAdmissionResult.Status.REENTRANT_ADMISSION, null,
                    "recursive admission from the mempool mutation lane is not allowed");
        }
        if (closed) {
            throw new IllegalStateException("mempool is closed");
        }
        MempoolAdmissionLimits effectiveLimits = limits != null ? limits : MempoolAdmissionLimits.unbounded();
        if (effectiveLimits.maxBytes() > 0 && txBytes.length > effectiveLimits.maxBytes()) {
            capacityRejections.incrementAndGet();
            return result(MempoolAdmissionResult.Status.BYTE_CAPACITY, null,
                    "transaction body exceeds the mempool byte limit");
        }
        byte[] owned = Arrays.copyOf(txBytes, txBytes.length);
        TxProjection projection;
        try {
            projection = TxProjection.of(owned);
        } catch (Exception e) {
            malformedRejections.incrementAndGet();
            return result(MempoolAdmissionResult.Status.MALFORMED, null,
                    "transaction projection failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        String txHash = projection.txHash();

        ensurePublished();
        if (isStale()) {
            requestRebuild("admission saw a stale mempool state");
        }
        if (status == Status.CATCHING_UP) {
            catchingUpRejections.incrementAndGet();
            return catchingUp(txHash);
        }

        long waitStarted = System.nanoTime();
        lockLane();
        long holdStarted = System.nanoTime();
        totalAdmissionWaitNanos += holdStarted - waitStarted;
        try {
            MempoolLedgerState s = published;
            if (s == null || status == Status.CATCHING_UP) {
                catchingUpRejections.incrementAndGet();
                return catchingUp(txHash);
            }
            MempoolEntry existing = s.indexes().byId().get(txHash);
            if (existing != null) {
                duplicateRejections++;
                return new MempoolAdmissionResult(MempoolAdmissionResult.Status.DUPLICATE, txHash,
                        copy(existing.transaction()), List.of(), "already present");
            }
            for (Outpoint input : projection.regularInputs()) {
                String spender = s.indexes().spentBy().get(input);
                if (spender != null) {
                    conflictRejections++;
                    return result(MempoolAdmissionResult.Status.CONFLICT, txHash,
                            "regular input is already claimed by " + spender + ": " + input.txHash() + "#"
                                    + input.index());
                }
            }
            Set<String> parents = s.indexes().parentsOf(projection);
            int addedIndexEntries = projection.outputs().size() + projection.regularInputs().size()
                    + newReferenceScripts(s.indexes(), projection) + parents.size() * 2;
            if (effectiveLimits.maxTransactions() > 0 && s.size() + 1 > effectiveLimits.maxTransactions()) {
                capacityRejections.incrementAndGet();
                return result(MempoolAdmissionResult.Status.TRANSACTION_CAPACITY, txHash,
                        "transaction-count limit reached");
            }
            if (effectiveLimits.maxBytes() > 0
                    && s.indexes().byteSize() + owned.length > effectiveLimits.maxBytes()) {
                capacityRejections.incrementAndGet();
                return result(MempoolAdmissionResult.Status.BYTE_CAPACITY, txHash, "transaction-byte limit reached");
            }
            if (effectiveLimits.maxUtxoIndexEntries() > 0
                    && s.indexes().entryCount() + addedIndexEntries > effectiveLimits.maxUtxoIndexEntries()) {
                capacityRejections.incrementAndGet();
                return result(MempoolAdmissionResult.Status.INDEX_CAPACITY, txHash, "UTXO-index limit reached");
            }
            return validateAndAppend(s, owned, projection, parents, origin, validator, acceptedListener);
        } finally {
            totalAdmissionHoldNanos += System.nanoTime() - holdStarted;
            lane.unlock();
        }
    }

    /** Under the lane. */
    private MempoolAdmissionResult validateAndAppend(MempoolLedgerState s, byte[] owned, TxProjection projection,
                                                     Set<String> parents, TxValidationRequest.Origin origin,
                                                     AdmissionValidator validator,
                                                     Consumer<MemPoolTransaction> acceptedListener) {
        String txHash = projection.txHash();
        // The admission holds a reference to the published base for its duration (§6): a concurrent swap cannot
        // free what it reads, and a shadow engine that outlives it retains the base again.
        MempoolBase base = s.base().retain();
        try {
            ValidationEnv env = null;
            String envFailure = null;
            try {
                env = base.env(envFactory);
            } catch (LedgerStateUnavailableException e) {
                envFailure = e.getMessage();
            }
            OverlayLedgerView view = s.overlay();
            List<VetoableEvent.Rejection> rejections = List.of();
            TxValidationOutcome outcome;
            long validationStarted = System.nanoTime();
            try {
                try (LedgerAdmissionScope scope = LedgerAdmissionScope.open(txHash, view, env, envFailure, origin,
                        base, base::ageMillis)) {
                    if (validator != null) {
                        rejections = validator.validate(owned, txHash, utxoResolver(view));
                    }
                    outcome = scope.outcome();
                }
                if ((rejections == null || rejections.isEmpty()) && outcome == null) {
                    // No listener ran the engine (for example the deprecated default-validator-enabled=false): the
                    // mempool cannot append a transaction without its effects, so it validates itself.
                    outcome = validate(new Request(owned, view, env, envFailure, origin, null));
                }
            } finally {
                long duration = System.nanoTime() - validationStarted;
                totalValidationNanos += duration;
                if (duration >= SLOW_VALIDATION_NANOS) {
                    slowValidations++;
                }
            }
            if (ledgerStateUnavailable(outcome, rejections)) {
                // Not a verdict: the state the engine needs is not readable yet (for example the admission window
                // across the Conway bootstrap boundary, decision 6a). The submitter retries, as while catching up.
                catchingUpRejections.incrementAndGet();
                return result(MempoolAdmissionResult.Status.CATCHING_UP, txHash,
                        "ledger state unavailable for validation; retry later");
            }
            if (rejections != null && !rejections.isEmpty()) {
                ledgerRejections++;
                return new MempoolAdmissionResult(MempoolAdmissionResult.Status.LEDGER_REJECTED, txHash, null,
                        rejections, "ledger validation rejected transaction");
            }
            if (outcome instanceof TxValidationOutcome.Invalid invalid) {
                ledgerRejections++;
                return new MempoolAdmissionResult(MempoolAdmissionResult.Status.LEDGER_REJECTED, txHash, null,
                        rejections(invalid), "ledger validation rejected transaction");
            }
            TxValidationOutcome.Valid valid = (TxValidationOutcome.Valid) outcome;
            OverlayLedgerView next;
            MempoolEntry entry;
            MempoolIndexes indexes;
            try {
                next = view.apply(valid.effects());
                MemPoolTransaction transaction = new MemPoolTransaction(cursor.incrementAndGet(),
                        HexUtil.decodeHexString(txHash), owned, TxBodyType.CONWAY);
                entry = new MempoolEntry(transaction, projection, valid.validated(), origin, parents);
                indexes = s.indexes().add(entry);
            } catch (RuntimeException e) {
                ledgerRejections++;
                return new MempoolAdmissionResult(MempoolAdmissionResult.Status.LEDGER_REJECTED, txHash, null,
                        List.of(new VetoableEvent.Rejection("ENGINE.EffectsNotApplicable",
                                "the effects of the accepted transaction cannot be applied: " + e.getMessage())),
                        "ledger validation rejected transaction");
            }
            MempoolLedgerState appended = s.append(entry, next, indexes, ++mempoolGeneration, ++mutationSeq,
                    rebuildInProgress);
            published = appended;
            try {
                if (acceptedListener != null) {
                    acceptedListener.accept(copy(entry.transaction()));
                }
            } catch (RuntimeException | Error e) {
                // Undo as a removal (a new generation), so a rebuild that saw the append cannot publish it.
                published = appended.removal(s.entries(), s.overlay(), s.indexes(), ++mempoolGeneration,
                        ++mutationSeq, rebuildInProgress);
                throw e;
            }
            return new MempoolAdmissionResult(MempoolAdmissionResult.Status.ACCEPTED, txHash,
                    copy(entry.transaction()), List.of(), "accepted");
        } finally {
            base.release();
        }
    }

    /** UTxO reads of the validation event's listeners: the published overlay, the same view the engine reads. */
    private static Function<Outpoint, Utxo> utxoResolver(OverlayLedgerView view) {
        return outpoint -> switch (view.utxo(outpoint)) {
            case Lookup.Present<UtxoEntry> p -> UtxoConversions.toUtxo(p.value());
            case Lookup.Absent<UtxoEntry> a -> null;
            case Lookup.Unavailable<UtxoEntry> u -> throw new LedgerStateUnavailableException(u.reason());
        };
    }

    /**
     * Whether the admission failed only because ledger state was unavailable: every engine failure is
     * {@code ENGINE.LedgerStateUnavailable} and no listener rejected for another reason.
     */
    private static boolean ledgerStateUnavailable(TxValidationOutcome outcome,
                                                  List<VetoableEvent.Rejection> rejections) {
        return outcome instanceof TxValidationOutcome.Invalid invalid
                && invalid.failures().stream().allMatch(f -> f.rule() == LedgerRuleName.ENGINE
                        && LedgerFailure.LEDGER_STATE_UNAVAILABLE.equals(f.constructor()))
                && (rejections == null || rejections.stream()
                        .allMatch(r -> LedgerFailure.LEDGER_STATE_UNAVAILABLE.equals(r.source())));
    }

    private static List<VetoableEvent.Rejection> rejections(TxValidationOutcome.Invalid invalid) {
        List<VetoableEvent.Rejection> out = new ArrayList<>();
        for (LedgerFailure failure : invalid.failures()) {
            ValidationError error = failure.toValidationError();
            out.add(new VetoableEvent.Rejection(error.rule(), error.message()));
        }
        return out;
    }

    private MempoolAdmissionResult catchingUp(String txHash) {
        return result(MempoolAdmissionResult.Status.CATCHING_UP, txHash,
                "the mempool is catching up with the canonical chain; retry later");
    }

    /**
     * Publishes an empty state when there is none yet, or when the published state is empty over a base that had
     * no canonical state (for example before the snapshot source was installed). The base is acquired outside the
     * lane.
     */
    private void ensurePublished() {
        MempoolLedgerState s = published;
        if (closed || s != null && !(s.isEmpty() && s.base().isUnavailable())) {
            return;
        }
        MempoolBase base = acquireBase();
        boolean transferred = false;
        try {
            if (s != null && base.isUnavailable()) {
                return;
            }
            lockLane();
            try {
                MempoolLedgerState current = published;
                if (current == s && !closed) {
                    swap(MempoolLedgerState.fresh(base, OverlayLedgerView.over(base.view()), List.of(),
                            MempoolIndexes.EMPTY, ++mempoolGeneration, ++mutationSeq));
                    transferred = true;
                }
            } finally {
                lane.unlock();
            }
        } finally {
            if (!transferred) {
                base.release();
            }
        }
    }

    // ================================================================== rebuild

    /** Schedules a rebuild; triggers that arrive while one runs are coalesced into one follow-up. */
    public void requestRebuild(String reason) {
        if (closed) {
            return;
        }
        pending.set(true);
        if (workerScheduled.compareAndSet(false, true)) {
            try {
                rebuildExecutor.execute(this::workerLoop);
            } catch (RejectedExecutionException e) {
                workerScheduled.set(false);
                log.debug("Mempool rebuild not scheduled ({}): {}", reason, e.toString());
            }
        }
    }

    private void workerLoop() {
        try {
            while (!closed && pending.getAndSet(false)) {
                try {
                    rebuild(false);
                } catch (VirtualMachineError e) {
                    throw e;
                } catch (Throwable t) {
                    log.error("Mempool rebuild failed: {}", t.toString(), t);
                }
            }
        } finally {
            workerScheduled.set(false);
            if (!closed && pending.get() && workerScheduled.compareAndSet(false, true)) {
                try {
                    rebuildExecutor.execute(this::workerLoop);
                } catch (RejectedExecutionException e) {
                    workerScheduled.set(false);
                }
            }
        }
    }

    /**
     * Runs one rebuild cycle on the calling thread (ADR-056 §6 steps 1–8), even when the published state is
     * already fresh: off-lane attempts, then the synchronous fallback, then {@link Status#CATCHING_UP}. Cycles are
     * serialised, so this never runs next to the worker's.
     *
     * @return true when a state was published
     */
    public boolean rebuildNow() {
        return rebuild(true);
    }

    private boolean rebuild(boolean force) {
        rebuildLock.lock();
        try {
            if (closed) {
                return false;
            }
            if (!force && status == Status.READY) {
                MempoolLedgerState s = published;
                if (s != null && !s.base().isUnavailable() && s.mark().equals(source.current())) {
                    return false; // already fresh (a coalesced trigger)
                }
            }
            long started = System.nanoTime();
            try {
                for (int restarts = 0; restarts < settings.maxRestarts(); restarts++) {
                    if (closed) {
                        return false;
                    }
                    if (attempt(false) == Attempt.PUBLISHED) {
                        ready();
                        return true;
                    }
                }
                synchronousFallbacks.incrementAndGet();
                for (int i = 0; i < settings.syncAttempts(); i++) {
                    if (closed) {
                        return false;
                    }
                    if (attempt(true) == Attempt.PUBLISHED) {
                        ready();
                        return true;
                    }
                }
                enterCatchingUp();
                return false;
            } finally {
                lastRebuildMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            }
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * One attempt. Off-lane: steps 1–5. Synchronous (step 6): the base is acquired outside the lane exactly as in
     * step 1, then the lane is held through steps 2–4, so only a canonical writer can invalidate the attempt.
     */
    private Attempt attempt(boolean synchronous) {
        MempoolBase base = acquireBase();                                     // step 1, outside the lane
        boolean transferred = false;
        try {
            CanonicalMark target = base.mark();
            if (base.isUnavailable()) {
                // No canonical state (no snapshot source, database closing). An empty mempool publishes the base
                // anyway, so admissions fail closed with LedgerStateUnavailable (invariant 2); a non-empty one
                // cannot re-validate its transactions, so it neither drops them nor publishes stale: discard, and
                // eventually CATCHING_UP.
                lockLane();
                try {
                    MempoolLedgerState s = published;
                    if (!closed && (s == null || s.isEmpty()) && source.current().equals(target)) {
                        swap(MempoolLedgerState.fresh(base, OverlayLedgerView.over(base.view()), List.of(),
                                MempoolIndexes.EMPTY, ++mempoolGeneration, ++mutationSeq));
                        transferred = true;
                        notifyPublished(target, published.generation());
                        return Attempt.PUBLISHED;
                    }
                } finally {
                    lane.unlock();
                }
                return discard(target, "no canonical state: " + base.unavailableReason());
            }
            if (synchronous) {
                lockLane();                                                   // held through steps 2-4
                try {
                    if (closed) {
                        return discard(target, "the mempool is closed");
                    }
                    MempoolLedgerState s = published;
                    observer.foldStarted(target, s != null ? s.size() : 0, true);
                    Fold r = fold(s != null ? s.entries() : List.of(), base);
                    observer.foldCompleted(target, r.kept.size(), r.dropped.size());
                    if (!source.current().equals(target)) {
                        return discard(target, "the canonical generation or target epoch moved during the attempt");
                    }
                    long generation = publishRebuild(r, target);
                    transferred = true;
                    notifyPublished(target, generation);
                    return Attempt.PUBLISHED;
                } finally {
                    lane.unlock();
                }
            }
            MempoolLedgerState s;
            lockLane();                                                       // step 2
            try {
                s = published;
                rebuildInProgress = true;
            } finally {
                lane.unlock();
            }
            try {
                observer.foldStarted(target, s != null ? s.size() : 0, false);
                Fold r = fold(s != null ? s.entries() : List.of(), base);    // step 3, outside the lane
                observer.foldCompleted(target, r.kept.size(), r.dropped.size());
                lockLane();                                                   // step 4
                try {
                    if (closed) {
                        return discard(target, "the mempool is closed");
                    }
                    MempoolLedgerState p = published;
                    List<MempoolEntry> appended = s == null ? (p == null ? List.of() : null)
                            : MempoolLedgerState.appendsSince(s, p);
                    if (appended == null) {                                   // 4.1 mempool descent
                        return discard(target, "the mempool changed by a removal, eviction or clear during the fold");
                    }
                    if (!appended.isEmpty()) {
                        observer.reconcilingAppends(target, appended.stream().map(MempoolEntry::txHash).toList());
                        for (MempoolEntry e : appended) {
                            r.revalidate(e);
                        }
                    }
                    if (!source.current().equals(target)) {                   // 4.2 freshness, last before swap
                        return discard(target, "the canonical generation or target epoch moved during the rebuild");
                    }
                    long generation = publishRebuild(r, target);              // 4.3 swap
                    transferred = true;                                       // before any callback can throw
                    notifyPublished(target, generation);
                    return Attempt.PUBLISHED;
                } finally {
                    lane.unlock();
                }
            } finally {
                rebuildInProgress = false;
            }
        } finally {
            if (!transferred) {
                base.release();                                               // step 5: a discard releases its base
            }
        }
    }

    /**
     * Under the lane: step 8, ownership transfer. The new state keeps the base reference the rebuild acquired; the
     * caller marks it transferred before notifying anyone, so a throwing callback cannot release it twice.
     *
     * @return the published state's mempool generation
     */
    private long publishRebuild(Fold r, CanonicalMark target) {
        MempoolLedgerState next = MempoolLedgerState.fresh(r.base, r.overlay, r.kept, r.indexes, ++mempoolGeneration,
                ++mutationSeq);
        swap(next);
        rebuildsPublished.incrementAndGet();
        revalidationDrops.addAndGet(r.dropped.size());
        if (!r.dropped.isEmpty() && log.isDebugEnabled()) {
            log.debug("Mempool rebuild for generation {} dropped {} transactions: {}", target.generation(),
                    r.dropped.size(), r.dropped.stream().map(MempoolEntry::txHash).toList());
        }
        return next.generation();
    }

    /** Observer failures are logged; they never undo a publication. */
    private void notifyPublished(CanonicalMark target, long generation) {
        try {
            observer.published(target, generation);
        } catch (RuntimeException e) {
            log.warn("Mempool rebuild observer failed: {}", e.toString());
        }
    }

    private Attempt discard(CanonicalMark target, String reason) {
        rebuildsDiscarded.incrementAndGet();
        observer.discarded(target, reason);
        log.debug("Mempool rebuild for generation {} discarded: {}", target.generation(), reason);
        return Attempt.DISCARDED;
    }

    /**
     * Under the lane: publishes {@code next} and retires the replaced state. The published slot owns one reference
     * to the published base; when the base changes, the old base's slot reference is released, and it is freed
     * once every admission and frozen shadow view that retained it released theirs.
     */
    private void swap(MempoolLedgerState next) {
        MempoolLedgerState old = published;
        published = next;
        if (old != null && old.base() != next.base()) {
            old.base().release();
        }
    }

    private void ready() {
        if (status != Status.READY) {
            status = Status.READY;
            log.info("Mempool caught up with the canonical chain (generation {})",
                    published != null ? published.mark().generation() : -1);
            observer.statusChanged(Status.READY);
        }
    }

    /** Step 7. The worker retries on the next canonical publication, and after a delay. */
    private void enterCatchingUp() {
        if (status != Status.CATCHING_UP) {
            status = Status.CATCHING_UP;
            catchingUpEntered.incrementAndGet();
            log.warn("Mempool rebuilds cannot catch up with the canonical chain; admission is paused "
                    + "(retryable) until a rebuild publishes");
            observer.statusChanged(Status.CATCHING_UP);
        }
        if (retryScheduler != null && retryScheduled.compareAndSet(false, true)) {
            try {
                retryScheduler.schedule(() -> {
                    retryScheduled.set(false);
                    requestRebuild("catching-up retry");
                }, settings.catchingUpRetryMs(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                retryScheduled.set(false);
            }
        }
    }

    /** Step 3 (and the synchronous fold): re-validates {@code entries} in order over a fresh overlay of {@code base}. */
    private Fold fold(List<MempoolEntry> entries, MempoolBase base) {
        Fold r = new Fold(base, OverlayLedgerView.over(base.view()), new ArrayList<>(entries.size()),
                MempoolIndexes.EMPTY);
        for (MempoolEntry e : entries) {
            if (closed) {
                break;
            }
            r.revalidate(e);
        }
        return r;
    }

    /** A candidate state under construction (a rebuild's R, or a removal's re-applied suffix). Not thread-safe. */
    private final class Fold {
        final MempoolBase base;
        OverlayLedgerView overlay;
        final List<MempoolEntry> kept;
        MempoolIndexes indexes;
        final List<MempoolEntry> dropped = new ArrayList<>();
        private final ValidationEnv env;
        private final String envFailure;

        Fold(MempoolBase base, OverlayLedgerView overlay, List<MempoolEntry> kept, MempoolIndexes indexes) {
            this.base = base;
            this.overlay = overlay;
            this.kept = kept;
            this.indexes = indexes;
            ValidationEnv e = null;
            String failure = null;
            try {
                e = base.env(envFactory);
            } catch (LedgerStateUnavailableException ex) {
                failure = ex.getMessage();
            }
            this.env = e;
            this.envFailure = failure;
        }

        /**
         * Validates {@code e} with rule {@code MEMPOOL} on top of the candidate, passing its provenance as
         * {@code previous}; the engine decides between re-application and full validation (§6).
         *
         * @return true when kept
         */
        boolean revalidate(MempoolEntry e) {
            if (indexes.byId().containsKey(e.txHash())) {
                dropped.add(e);
                return false;
            }
            for (Outpoint input : e.projection().regularInputs()) {
                if (indexes.spentBy().containsKey(input)) {
                    dropped.add(e);
                    return false;
                }
            }
            // A SYNC verdict is never re-used for admission (§6); mempool entries never carry one, but be explicit.
            ValidatedTx previous = e.validated().origin() == TxValidationRequest.Origin.SYNC ? null : e.validated();
            Request request = new Request(e.txBytes(), overlay, env, envFailure, e.origin(), previous);
            TxValidationOutcome outcome = validate(request);
            observer.revalidated(e.txHash(), request.asRequest(), outcome);
            if (!(outcome instanceof TxValidationOutcome.Valid valid)) {
                dropped.add(e);
                return false;
            }
            try {
                OverlayLedgerView next = overlay.apply(valid.effects());
                MempoolEntry updated = e.revalidated(valid.validated(), indexes.parentsOf(e.projection()));
                indexes = indexes.add(updated);
                overlay = next;
                kept.add(updated);
            } catch (RuntimeException ex) {
                dropped.add(e);
                return false;
            }
            (valid.reapplied() ? reapplications : fullRevalidations).incrementAndGet();
            return true;
        }
    }

    /** A validation request whose environment may be missing (then it fails closed). */
    private record Request(byte[] txCbor, OverlayLedgerView view, ValidationEnv env, String envFailure,
                           TxValidationRequest.Origin origin, ValidatedTx previous) {
        TxValidationRequest asRequest() {
            return env == null ? null
                    : new TxValidationRequest(txCbor, view, env, TxValidationRequest.Rule.MEMPOOL, origin, previous);
        }
    }

    private TxValidationOutcome validate(Request request) {
        if (request.env() == null) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable(
                    request.envFailure() != null ? request.envFailure() : "no validation environment"));
        }
        try {
            return engine.validate(request.asRequest());
        } catch (RuntimeException e) {
            // The SPI forbids throwing; fail closed.
            return TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.ENGINE, "EngineFailure",
                    LedgerFailure.Phase.PHASE_1, e.toString()));
        }
    }

    // ================================================================== block production

    /** Attempts before a selection whose canonical generation keeps moving gives up (and selects nothing). */
    private static final int BLOCK_SELECTION_ATTEMPTS = 3;

    /**
     * Consecutive block selections a candidate may fail transiently before it is removed as invalid: a failure
     * that persists this long (for example an engine that cannot derive a transaction's effects) is not transient.
     */
    static final int TRANSIENT_SKIP_LIMIT = 32;

    /** Consecutive transient block-selection failures per mempool transaction (pruned to the published entries). */
    private final Map<String, Integer> transientSkips = new ConcurrentHashMap<>();

    /**
     * Selects transactions for a block forged at {@code forgeSlot} (ADR-056 §6, "Block production").
     *
     * <p>Selection never relies on the mempool overlay: it acquires its own {@code BLOCK_BUILD} base (a canonical
     * snapshot ticked to {@code forgeSlot}, forecast horizon based on the slot after the tip), builds a fresh
     * block-local overlay over it, and validates the published entries in mempool order with rule {@code LEDGER},
     * origin {@code BLOCK_BUILD}, passing each entry's {@code ValidatedTx} as {@code previous}; the engine's
     * invalidation rules decide between re-application and full validation. A valid candidate's effects are
     * applied to the overlay before the next candidate is validated, so dependent chains (UTxO, certificate and
     * governance dependencies) are selected in order.</p>
     *
     * <p>Failures: a candidate that fails a ledger rule is not selected and is removed with its dependents through
     * {@link #removeInvalidated} after the selection (deferred to the pending rebuild while the published state
     * lags, so a transaction invalid only because it was just confirmed never cascades its dependents). A transient
     * failure (an unavailable read, an engine failure) skips the candidate without removal, and every later failure
     * of the same selection is skipped without removal too, since it may depend on the skipped one.</p>
     *
     * <p>If the canonical generation changes while candidates are validated, the selection is discarded and redone
     * on a new base (at most {@value #BLOCK_SELECTION_ATTEMPTS} times, then nothing is selected). The producer
     * checks {@link #canonicalGeneration()} again inside its store section ({@code selectionCurrent}).</p>
     *
     * <p>Lock order: the base is acquired while neither the lane nor the canonical gate is held; the published
     * entries are read without the lane, so admission is never blocked by a selection.</p>
     *
     * @param forgeSlot the slot of the block being forged; negative for the slot after the tip
     */
    public BlockSelection selectForBlock(long forgeSlot) {
        if (closed || status == Status.CATCHING_UP) {
            return BlockSelection.empty();
        }
        if (lane.isHeldByCurrentThread() || source.isHeldByCurrentThread()) {
            lockOrderViolations.incrementAndGet();
            throw new IllegalStateException("ADR-056 lock order: block selection with the mempool lane or the "
                    + "canonical gate held");
        }
        long started = System.nanoTime();
        try {
            BlockSelection selection = null;
            for (int attempt = 0; attempt < BLOCK_SELECTION_ATTEMPTS && selection == null; attempt++) {
                MempoolLedgerState s = published;
                if (s == null || s.isEmpty()) {
                    return BlockSelection.empty();
                }
                selection = selectOnce(s.entries(), forgeSlot);
                if (selection != null && selection.generation() >= 0
                        && source.current().generation() != selection.generation()) {
                    blockSelectionRedos.incrementAndGet();
                    log.debug("Block selection for slot {} discarded: the canonical generation moved from {} to {}",
                            forgeSlot, selection.generation(), source.current().generation());
                    selection = null;
                }
            }
            if (selection == null) {
                return BlockSelection.empty();
            }
            selection = applyTransientLimit(selection);
            blockSelections.incrementAndGet();
            blockSelectionRejected.addAndGet(selection.rejected().size());
            blockSelectionSkipped.addAndGet(selection.skipped().size());
            if (!selection.rejected().isEmpty()) {
                removeInvalidated(new LinkedHashSet<>(selection.rejected()));
            }
            return selection;
        } finally {
            lastBlockSelectionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        }
    }

    /**
     * Counts the transient failures of this (final) selection per transaction; a transaction whose failures reach
     * {@link #TRANSIENT_SKIP_LIMIT} consecutive selections is rejected (removed with its dependents). Counters of
     * transactions selected, rejected, or no longer in the mempool are dropped.
     */
    private BlockSelection applyTransientLimit(BlockSelection selection) {
        MempoolLedgerState s = published;
        if (s != null) {
            transientSkips.keySet().removeIf(hash -> !s.indexes().byId().containsKey(hash));
        }
        for (byte[] tx : selection.transactions()) {
            transientSkips.remove(TransactionUtil.getTxHash(tx));
        }
        selection.rejected().forEach(transientSkips::remove);
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, String> failure : selection.transientFailures().entrySet()) {
            int count = transientSkips.merge(failure.getKey(), 1, Integer::sum);
            if (count >= TRANSIENT_SKIP_LIMIT) {
                expired.add(failure.getKey());
                transientSkips.remove(failure.getKey());
                log.warn("Block selection removes {}: its validation failed in {} consecutive selections ({}); a "
                        + "failure that persists is not transient", failure.getKey(), count, failure.getValue());
            }
        }
        if (expired.isEmpty()) {
            return selection;
        }
        List<String> rejected = new ArrayList<>(selection.rejected());
        rejected.addAll(expired);
        List<String> skipped = new ArrayList<>(selection.skipped());
        skipped.removeAll(expired);
        return new BlockSelection(selection.transactions(), selection.generation(), selection.forgeSlot(), rejected,
                skipped, selection.reapplied(), selection.transientFailures());
    }

    /** @return the published canonical generation (a volatile read; never takes the gate) */
    public long canonicalGeneration() {
        return source.current().generation();
    }

    /** One selection attempt over {@code entries}; {@code null} only when the attempt must be redone. */
    private BlockSelection selectOnce(List<MempoolEntry> entries, long forgeSlot) {
        MempoolBase base = source.acquireForBlock(forgeSlot);
        try {
            if (base.isUnavailable()) {
                log.debug("Block selection for slot {} skips the mempool: {}", forgeSlot, base.unavailableReason());
                return BlockSelection.empty();
            }
            ValidationEnv env;
            try {
                env = base.env(envFactory);
            } catch (LedgerStateUnavailableException e) {
                log.debug("Block selection for slot {} skips the mempool: {}", forgeSlot, e.getMessage());
                return BlockSelection.empty();
            }
            // Bound the work: the builder keeps a prefix that fits the block's size limit, so candidates past
            // twice that size can never be forged in this block.
            long byteCap = selectionByteCap(base);
            OverlayLedgerView overlay = OverlayLedgerView.over(base.view());
            List<byte[]> selected = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            Map<String, String> transientFailures = new LinkedHashMap<>();
            boolean tainted = false;
            long bytes = 0;
            int reapplied = 0;
            for (MempoolEntry e : entries) {
                if (closed) {
                    return null;
                }
                if (bytes >= byteCap) {
                    break;
                }
                if (!e.validated().phase2Valid()) {
                    // Never forged: the builder cannot encode invalid_txs (ADR-056 §6, decision 6).
                    rejected.add(e.txHash());
                    continue;
                }
                ValidatedTx previous = e.validated().origin() == TxValidationRequest.Origin.SYNC ? null
                        : e.validated();
                TxValidationRequest request = new TxValidationRequest(e.txBytes(), overlay, env,
                        TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.BLOCK_BUILD, previous);
                TxValidationOutcome outcome;
                try {
                    outcome = engine.validate(request);
                } catch (RuntimeException ex) {
                    outcome = TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.ENGINE,
                            "EngineFailure", LedgerFailure.Phase.PHASE_1, ex.toString()));
                }
                observer.blockCandidateValidated(e.txHash(), request, outcome);
                if (outcome instanceof TxValidationOutcome.Valid valid && !valid.validated().phase2Valid()) {
                    // Phase-2-invalid is only a SYNC verdict; the builder cannot encode it (decision 6): rejected.
                    rejected.add(e.txHash());
                    continue;
                }
                if (outcome instanceof TxValidationOutcome.Valid valid) {
                    try {
                        overlay = overlay.apply(valid.effects());
                    } catch (RuntimeException ex) {
                        skipped.add(e.txHash());
                        transientFailures.put(e.txHash(), "effects not applicable: " + ex.getMessage());
                        tainted = true;
                        continue;
                    }
                    selected.add(e.txBytes());
                    bytes += e.size();
                    if (valid.reapplied()) {
                        reapplied++;
                        blockSelectionReapplications.incrementAndGet();
                    } else {
                        blockSelectionFullValidations.incrementAndGet();
                    }
                    continue;
                }
                List<LedgerFailure> failures = outcome instanceof TxValidationOutcome.Invalid invalid
                        ? invalid.failures() : List.of();
                if (transientFailure(failures)) {
                    skipped.add(e.txHash());
                    transientFailures.put(e.txHash(), failures.stream().map(LedgerFailure::qualifiedName).toList()
                            .toString());
                    tainted = true;
                } else if (tainted) {
                    skipped.add(e.txHash());
                } else {
                    rejected.add(e.txHash());
                    if (log.isDebugEnabled()) {
                        log.debug("Block selection for slot {} rejects {}: {}", forgeSlot, e.txHash(),
                                failures.stream().map(LedgerFailure::qualifiedName).toList());
                    }
                }
            }
            return new BlockSelection(selected, base.generation(), base.targetSlot(), rejected, skipped, reapplied,
                    transientFailures);
        } finally {
            base.release();
        }
    }

    private static long selectionByteCap(MempoolBase base) {
        try {
            Lookup<ProtocolParams> params = base.view().protocolParams();
            if (params instanceof Lookup.Present<ProtocolParams> p
                    && p.value().getMaxBlockSize() != null && p.value().getMaxBlockSize() > 0) {
                return 2L * p.value().getMaxBlockSize();
            }
        } catch (RuntimeException ignored) {
            // no cap
        }
        return Long.MAX_VALUE;
    }

    /**
     * An engine failure other than the Yano policy constructors is transient (an unavailable read, a busy or
     * unhealthy engine): the candidate may be valid on the next attempt, so it is never removed for it.
     */
    private static boolean transientFailure(List<LedgerFailure> failures) {
        if (failures.isEmpty()) {
            return true;
        }
        for (LedgerFailure failure : failures) {
            if (failure.rule() == LedgerRuleName.ENGINE
                    && !LedgerFailure.PHASE2_INVALID_TX_NOT_SUPPORTED.equals(failure.constructor())
                    && !"DecodingFailure".equals(failure.constructor())) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== removal (truncate and reapply)

    /**
     * Under the lane: removes {@code roots} by truncating the overlay before the earliest of them and re-applying
     * the remaining suffix; suffix transactions that now fail are dropped too (§6, "Removal, eviction or clear").
     *
     * @param removedOut receives the removed transaction ids, or {@code null}
     * @return the number of transactions removed
     */
    private int truncateAndReapply(Set<String> roots, List<String> removedOut) {
        MempoolLedgerState s = published;
        if (s == null || roots.isEmpty()) {
            return 0;
        }
        List<MempoolEntry> entries = s.entries();
        int first = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (roots.contains(entries.get(i).txHash())) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return 0;
        }
        MempoolIndexes indexes = s.indexes();
        for (int i = first; i < entries.size(); i++) {
            indexes = indexes.remove(entries.get(i));
        }
        Fold f = new Fold(s.base(), s.overlay().truncateTo(first), new ArrayList<>(entries.subList(0, first)),
                indexes);
        int direct = 0;
        List<String> removed = new ArrayList<>();
        for (int i = first; i < entries.size(); i++) {
            MempoolEntry e = entries.get(i);
            if (roots.contains(e.txHash())) {
                removed.add(e.txHash());
                direct++;
            } else if (!f.revalidate(e)) {
                removed.add(e.txHash());
            }
        }
        published = s.removal(f.kept, f.overlay, f.indexes, ++mempoolGeneration, ++mutationSeq, rebuildInProgress);
        cascadedRemovals += removed.size() - direct;
        if (removedOut != null) {
            removedOut.addAll(removed);
        }
        return removed.size();
    }

    @Override
    public void clear() {
        lockLane();
        try {
            MempoolLedgerState s = published;
            if (s == null || s.isEmpty()) {
                return;
            }
            published = s.removal(List.of(), s.overlay().truncateTo(0), MempoolIndexes.EMPTY, ++mempoolGeneration,
                    ++mutationSeq, rebuildInProgress);
        } finally {
            lane.unlock();
        }
    }

    /**
     * Confirmation removal is a canonical change: the rebuild against the new tip drops confirmed transactions and
     * keeps their dependents (whose inputs are now canonical). Removing them here, against the old base, would
     * cascade the dependents, so this only schedules the rebuild.
     */
    @Override
    public int removeByTxHashes(Set<String> txHashes) {
        requestRebuild("confirmed transactions");
        return 0;
    }

    /** As {@link #removeByTxHashes}: canonically consumed inputs are the rebuild's to reconcile. */
    @Override
    public int removeConflictingInputs(Set<Outpoint> consumedOutpoints) {
        if (consumedOutpoints != null && !consumedOutpoints.isEmpty()) {
            requestRebuild("canonically consumed inputs");
        }
        return 0;
    }

    /**
     * Removes transactions found invalid (block selection) with their dependents. While the published state is
     * behind the canonical tip the removal is deferred to the pending rebuild, which re-validates everything
     * against the new tip: a transaction invalid only because it was confirmed must not cascade its dependents.
     */
    @Override
    public int removeInvalidated(Set<String> txHashes) {
        Set<String> ids = normalize(txHashes);
        if (ids.isEmpty()) {
            return 0;
        }
        if (!isStale()) {
            lockLane();
            try {
                // Re-checked under the lane: a rebuild may not have swapped yet, or the tip moved since.
                if (!isStale()) {
                    return truncateAndReapply(ids, null);
                }
            } finally {
                lane.unlock();
            }
        }
        deferredRemovals.incrementAndGet();
        requestRebuild("invalidated transactions while stale");
        return 0;
    }

    @Override
    public List<String> evictTransaction(String txHash) {
        if (!lane.tryLock()) {
            throw new IllegalStateException("Mempool busy; retry eviction");
        }
        try {
            assertGateNotHeld();
            List<String> removed = new ArrayList<>();
            truncateAndReapply(normalize(Set.of(txHash)), removed);
            return List.copyOf(removed);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int evictOldest(int count) {
        if (count <= 0) {
            return 0;
        }
        lockLane();
        try {
            MempoolLedgerState s = published;
            if (s == null) {
                return 0;
            }
            Set<String> roots = new LinkedHashSet<>();
            for (MempoolEntry e : s.entries()) {
                roots.add(e.txHash());
                if (roots.size() >= count) {
                    break;
                }
            }
            return truncateAndReapply(roots, null);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int evictOldestUntilBytesAtMost(long maxBytes) {
        long target = Math.max(0, maxBytes);
        lockLane();
        try {
            MempoolLedgerState s = published;
            if (s == null) {
                return 0;
            }
            Map<String, List<String>> children = new HashMap<>();
            for (MempoolEntry e : s.entries()) {
                for (String parent : e.parents()) {
                    children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(e.txHash());
                }
            }
            Set<String> roots = new LinkedHashSet<>();
            Set<String> planned = new HashSet<>();
            long projected = s.indexes().byteSize();
            for (MempoolEntry e : s.entries()) {
                if (projected <= target) {
                    break;
                }
                if (planned.contains(e.txHash())) {
                    continue;
                }
                roots.add(e.txHash());
                ArrayDeque<String> queue = new ArrayDeque<>(List.of(e.txHash()));
                while (!queue.isEmpty()) {
                    String id = queue.removeFirst();
                    if (!planned.add(id)) {
                        continue;
                    }
                    MempoolEntry removed = s.indexes().byId().get(id);
                    if (removed != null) {
                        projected -= removed.size();
                    }
                    queue.addAll(children.getOrDefault(id, List.of()));
                }
            }
            return truncateAndReapply(roots, null);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int removeOlderThan(long beforeEpochMillis) {
        lockLane();
        try {
            MempoolLedgerState s = published;
            if (s == null) {
                return 0;
            }
            Set<String> roots = new LinkedHashSet<>();
            for (MempoolEntry e : s.entries()) {
                if (e.transaction().insertedAt() >= beforeEpochMillis) {
                    break;
                }
                roots.add(e.txHash());
            }
            return truncateAndReapply(roots, null);
        } finally {
            lane.unlock();
        }
    }

    /** A canonical rollback is reconciled by the rebuild (§6), not by a UTxO-only pass. */
    @Override
    public int revalidate(Function<Outpoint, Utxo> canonicalResolver) {
        requestRebuild("canonical rollback");
        return 0;
    }

    @Override
    public MemPoolTransaction addTransaction(byte[] txBytes) {
        MempoolAdmissionResult admission = tryAdmit(txBytes, TxValidationRequest.Origin.LOCAL, null,
                MempoolAdmissionLimits.unbounded(), null);
        if (admission.present()) {
            return admission.transaction();
        }
        throw new MempoolAdmissionException(admission);
    }

    @Override
    public MemPoolTransaction getNextTransaction() {
        lockLane();
        try {
            MempoolLedgerState s = published;
            if (s == null || s.isEmpty()) {
                return null;
            }
            MempoolEntry oldest = s.entries().getFirst();
            truncateAndReapply(Set.of(oldest.txHash()), null);
            return copy(oldest.transaction());
        } finally {
            lane.unlock();
        }
    }

    // ================================================================== queries (lock-free on the published state)

    @Override
    public UtxoOverlay utxoOverlay(byte[] subject, boolean credential) {
        MempoolLedgerState s = published;
        if (s == null) {
            return new UtxoOverlay(List.of(), Set.of());
        }
        MempoolIndexes idx = s.indexes();
        if (idx.spentBy().size() > 100_000 || idx.producedBy().size() > 100_000) {
            throw new IllegalStateException("Mempool query snapshot limit exceeded");
        }
        List<Utxo> selected = new ArrayList<>();
        long[] selectedBytes = {0};
        idx.producedBy().forEach((outpoint, produced) -> {
            TxProjection.SubjectKey key = produced.subject();
            if (key == null || !Arrays.equals(subject, credential ? key.payment() : key.address())
                    || idx.spentBy().containsKey(outpoint)) {
                return;
            }
            MempoolEntry owner = idx.byId().get(produced.owner());
            selectedBytes[0] += owner != null ? owner.size() : 0;
            if (selected.size() >= 1000 || selectedBytes[0] > 8 * 1024 * 1024) {
                throw new IllegalStateException("Mempool subject snapshot limit exceeded");
            }
            selected.add(copy(produced.utxo()));
        });
        return new UtxoOverlay(selected, new HashSet<>(idx.spentBy().keys()));
    }

    @Override
    public Map<Outpoint, Utxo> resolveUtxos(Collection<Outpoint> outpoints,
                                            Function<Outpoint, Utxo> canonicalResolver) {
        Objects.requireNonNull(outpoints, "outpoints");
        Function<Outpoint, Utxo> canonical = canonicalResolver != null ? canonicalResolver : ignored -> null;
        MempoolLedgerState s = published;
        Map<Outpoint, Utxo> resolved = new LinkedHashMap<>();
        for (Outpoint outpoint : new LinkedHashSet<>(outpoints)) {
            Objects.requireNonNull(outpoint, "outpoints must not contain null");
            Outpoint key;
            try {
                key = Outpoints.normalize(outpoint);
            } catch (RuntimeException e) {
                continue;
            }
            if (s != null) {
                if (s.indexes().spentBy().containsKey(key)) {
                    continue;
                }
                MempoolIndexes.Produced produced = s.indexes().producedBy().get(key);
                if (produced != null) {
                    resolved.put(outpoint, copy(produced.utxo()));
                    continue;
                }
            }
            Utxo utxo = canonical.apply(outpoint);
            if (utxo != null) {
                resolved.put(outpoint, copy(utxo));
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    @Override
    public Optional<byte[]> getScriptRefBytesByHash(String scriptHash) {
        String normalized = MempoolIndexes.normalizeScriptHash(scriptHash);
        MempoolLedgerState s = published;
        if (normalized == null || s == null) {
            return Optional.empty();
        }
        MempoolIndexes.RefScript script = s.indexes().refScripts().get(normalized);
        return script != null ? Optional.of(script.bytes().clone()) : Optional.empty();
    }

    @Override
    public boolean isEmpty() {
        MempoolLedgerState s = published;
        return s == null || s.isEmpty();
    }

    @Override
    public int size() {
        MempoolLedgerState s = published;
        return s == null ? 0 : s.size();
    }

    @Override
    public long byteSize() {
        MempoolLedgerState s = published;
        return s == null ? 0 : s.indexes().byteSize();
    }

    @Override
    public boolean contains(String txHash) {
        MempoolLedgerState s = published;
        String id = normalizeHash(txHash);
        return s != null && id != null && s.indexes().byId().containsKey(id);
    }

    @Override
    public MemPoolTransaction getTransaction(String txHash) {
        MempoolLedgerState s = published;
        String id = normalizeHash(txHash);
        MempoolEntry e = s != null && id != null ? s.indexes().byId().get(id) : null;
        return e != null ? copy(e.transaction()) : null;
    }

    @Override
    public List<MemPoolTransaction> snapshotTransactions(int maxCount, long maxBytes) {
        MempoolLedgerState s = published;
        if (s == null || maxCount <= 0 || maxBytes < 0) {
            return List.of();
        }
        List<MemPoolTransaction> snapshot = new ArrayList<>(Math.min(maxCount, s.size()));
        long selectedBytes = 0;
        for (MempoolEntry e : s.entries()) {
            // Consumers treat records as immutable; the mempool-owned body is shared, as DefaultMemPool does.
            MemPoolTransaction transaction = e.transaction();
            if (selectedBytes + transaction.size() > maxBytes) {
                break;
            }
            snapshot.add(transaction);
            selectedBytes += transaction.size();
            if (snapshot.size() >= maxCount) {
                break;
            }
        }
        return List.copyOf(snapshot);
    }

    @Override
    public MempoolStats stats() {
        MempoolLedgerState s = published;
        MempoolIndexes idx = s != null ? s.indexes() : MempoolIndexes.EMPTY;
        long subjectBytes = 0;
        long scriptBytes = 0;
        if (s != null) {
            for (MempoolEntry e : s.entries()) {
                subjectBytes += e.projection().subjects().size() * 192L;
            }
            for (var e : idx.refScripts()) {
                scriptBytes += 96L + e.getValue().bytes().length;
            }
        }
        long estimatedIndexBytes = idx.producedBy().size() * 256L + subjectBytes + idx.spentBy().size() * 96L
                + scriptBytes + idx.edgeCount() * 256L;
        // Lock-free: the lane-owned counters are read racily (monotonic diagnostics).
        return new MempoolStats(idx.byId().size(), idx.byteSize(), idx.entryCount(), idx.producedBy().size(),
                idx.spentBy().size(), idx.refScripts().size(), idx.edgeCount(), estimatedIndexBytes,
                duplicateRejections, conflictRejections, capacityRejections.get(), malformedRejections.get(),
                ledgerRejections, cascadedRemovals, lane.getQueueLength(), totalAdmissionWaitNanos,
                totalAdmissionHoldNanos, totalValidationNanos, slowValidations);
    }

    /** @return the ledger-state health and counters (health endpoint, metrics) */
    public LedgerMempoolStatus ledgerStatus() {
        MempoolLedgerState s = published;
        CanonicalMark current = source.current();
        return new LedgerMempoolStatus(status.name(), s != null ? s.generation() : 0,
                s != null ? s.mark().generation() : -1, current.generation(),
                s != null && !s.mark().equals(current), rebuildsPublished.get(), rebuildsDiscarded.get(),
                synchronousFallbacks.get(), catchingUpEntered.get(), catchingUpRejections.get(),
                reapplications.get(), fullRevalidations.get(), revalidationDrops.get(), deferredRemovals.get(),
                lastRebuildMillis, lockOrderViolations.get(), blockSelections.get(), blockSelectionRedos.get(),
                blockSelectionReapplications.get(), blockSelectionFullValidations.get(), blockSelectionRejected.get(),
                blockSelectionSkipped.get(), lastBlockSelectionMillis);
    }

    /** Test hook: the published state. */
    MempoolLedgerState published() {
        return published;
    }

    /** Test hook: whether the calling thread holds the mutation lane (lock-order probes). */
    boolean laneHeldByCurrentThread() {
        return lane.isHeldByCurrentThread();
    }

    // ================================================================== lifecycle

    /**
     * Closes the mempool: a rebuild in progress aborts at its next transaction or check (every swap re-checks the
     * closed flag under the lane, so nothing is published after the published base is released here); the wait for
     * it is bounded, so a closing subsystem does not block its health checks.
     */
    @Override
    public void close() {
        closed = true;
        AutoCloseable handle;
        synchronized (this) {
            handle = publicationHandle;
            publicationHandle = null;
        }
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception e) {
                log.debug("Unregistering the mempool publication listener failed: {}", e.toString());
            }
        }
        boolean rebuildStopped = false;
        try {
            rebuildStopped = rebuildLock.tryLock(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!rebuildStopped) {
            log.warn("A mempool rebuild did not stop within {} s; closing anyway (it cannot publish)",
                    CLOSE_WAIT_SECONDS);
        }
        try {
            lockLane();
            try {
                MempoolLedgerState s = published;
                published = null;
                if (s != null) {
                    s.base().release();
                }
            } finally {
                lane.unlock();
            }
        } finally {
            if (rebuildStopped) {
                rebuildLock.unlock();
            }
        }
    }

    // ================================================================== helpers

    /** Takes the lane, asserting the lock order (never while the canonical gate is held). */
    private void lockLane() {
        assertGateNotHeld();
        lane.lock();
    }

    private void assertGateNotHeld() {
        if (source.isHeldByCurrentThread()) {
            lockOrderViolations.incrementAndGet();
            throw new IllegalStateException(
                    "ADR-056 lock order: the mempool lane was requested while the canonical gate is held");
        }
    }

    /** Acquires a base, asserting the lock order (never while the lane is held). */
    private MempoolBase acquireBase() {
        if (lane.isHeldByCurrentThread()) {
            lockOrderViolations.incrementAndGet();
            throw new IllegalStateException(
                    "ADR-056 lock order: a canonical snapshot was requested while the mempool lane is held");
        }
        return source.acquire();
    }

    private static int newReferenceScripts(MempoolIndexes idx, TxProjection projection) {
        Set<String> added = new HashSet<>();
        for (Utxo utxo : projection.outputs().values()) {
            String hash = MempoolIndexes.normalizeScriptHash(utxo.referenceScriptHash());
            if (hash != null && utxo.scriptRef() != null && !idx.refScripts().containsKey(hash)) {
                added.add(hash);
            }
        }
        return added.size();
    }

    private static Set<String> normalize(Set<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String hash : hashes) {
            String id = normalizeHash(hash);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String normalizeHash(String hash) {
        if (hash == null) {
            return null;
        }
        try {
            byte[] bytes = HexUtil.decodeHexString(hash);
            return bytes.length == 32 ? HexUtil.encodeHexString(bytes) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static MemPoolTransaction copy(MemPoolTransaction transaction) {
        return new MemPoolTransaction(transaction.seqId(), transaction.txHash().clone(), transaction.txBytes().clone(),
                transaction.txBodyType(), transaction.insertedAt());
    }

    private static Utxo copy(Utxo utxo) {
        return new Utxo(utxo.outpoint(), utxo.address(), utxo.lovelace(),
                utxo.assets() != null ? List.copyOf(utxo.assets()) : List.of(), utxo.datumHash(),
                utxo.inlineDatum() != null ? utxo.inlineDatum().clone() : null, utxo.scriptRef(),
                utxo.referenceScriptHash(), utxo.collateralReturn(), utxo.slot(), utxo.blockNumber(), utxo.blockHash());
    }

    private static MempoolAdmissionResult result(MempoolAdmissionResult.Status status, String txHash, String detail) {
        return new MempoolAdmissionResult(status, txHash, null, List.of(), detail);
    }
}
