package org.yanoproject.runtime.validation;

import lombok.extern.slf4j.Slf4j;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;
import org.yanoproject.ledger.rules.shadow.Verdict;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs shadow engines asynchronously next to admission (ADR-056 §7) and records disagreements.
 *
 * <ul>
 *   <li><b>Frozen views.</b> Each job carries a view that owns its own reference to the admission's canonical
 *       snapshot (the caller checked the live-snapshot cap first, {@code CanonicalStateGate.admitShadow()});
 *       the job releases it exactly once, when it finishes, is dropped or expires.</li>
 *   <li><b>Bounded.</b> A fixed pool with a bounded queue; a full queue drops the job (counted).</li>
 *   <li><b>Max age.</b> A job older than {@code yano.validation.snapshot-max-age-ms}, measured from the
 *       snapshot's capture, is cancelled, releases its snapshot and is discarded: not counted as a
 *       disagreement.</li>
 *   <li><b>Comparison.</b> {@link Verdict}: valid/invalid and the first failure's rule and constructor.
 *       Disagreements are counted per (engine, rule), logged once per transaction id, and written as a
 *       {@link ShadowDumpBundle} when a dump directory is configured.</li>
 * </ul>
 * Shadow outcomes never affect admission.
 */
@Slf4j
public final class ShadowValidationRunner implements AutoCloseable {

    /** Default worker threads. */
    public static final int DEFAULT_THREADS = 2;
    /** Default queue capacity. */
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final int LOGGED_TX_CAPACITY = 10_000;

    /**
     * One admission to shadow.
     *
     * @param txHash           the transaction id
     * @param request          the shadow request: the admission's bytes, env, rule and origin over the frozen view
     * @param resource         what the frozen view holds (its snapshot reference); closed once by the runner
     * @param snapshotAgeMs    the snapshot's age when the job was submitted
     * @param admission        the admission engine (or the legacy validator) and its verdict
     * @param admissionReads   the admission engine's recorded reads (for dumps), or an empty list
     */
    public record ShadowJob(String txHash, TxValidationRequest request, AutoCloseable resource, long snapshotAgeMs,
                            RecordedOutcome admission, List<RecordingLedgerView.Read> admissionReads) {
        public ShadowJob {
            Objects.requireNonNull(txHash, "txHash");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(admission, "admission");
            admissionReads = admissionReads == null ? List.of() : List.copyOf(admissionReads);
        }
    }

    /**
     * Counters, as exported by the node's metrics.
     *
     * @param disagreements disagreements per engine, then per rule label ({@code yano_validation_disagreements_total})
     */
    public record Stats(long submitted, long compared, long agreements, long disagreementTotal,
                        Map<String, Map<String, Long>> disagreements, long droppedCap, long droppedUnavailable,
                        long droppedQueueFull, long expired, long engineErrors, long dumpsWritten, long dumpFailures,
                        int inFlight) {
    }

    private final List<LedgerValidationEngine> engines;
    private final Path dumpDir;
    private final long maxAgeMs;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService timer;

    private final LongAdder submitted = new LongAdder();
    private final LongAdder compared = new LongAdder();
    private final LongAdder agreements = new LongAdder();
    private final LongAdder disagreementTotal = new LongAdder();
    private final Map<String, Map<String, LongAdder>> disagreements = new ConcurrentHashMap<>();
    private final LongAdder droppedCap = new LongAdder();
    private final LongAdder droppedUnavailable = new LongAdder();
    private final LongAdder droppedQueueFull = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private final LongAdder engineErrors = new LongAdder();
    private final LongAdder dumpsWritten = new LongAdder();
    private final LongAdder dumpFailures = new LongAdder();
    private final AtomicLong inFlight = new AtomicLong();
    private final Map<String, Boolean> loggedTx = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > LOGGED_TX_CAPACITY;
        }
    });

    /**
     * @param engines       the shadow engines, run in order on each job
     * @param dumpDir       the replay-bundle directory, or {@code null}
     * @param maxAgeMs      {@code snapshot-max-age-ms}
     * @param threads       worker threads
     * @param queueCapacity jobs that may wait
     */
    public ShadowValidationRunner(List<LedgerValidationEngine> engines, Path dumpDir, long maxAgeMs, int threads,
                                  int queueCapacity) {
        this.engines = List.copyOf(engines);
        this.dumpDir = dumpDir;
        if (maxAgeMs <= 0) {
            throw new IllegalArgumentException("maxAgeMs must be > 0");
        }
        this.maxAgeMs = maxAgeMs;
        this.executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().daemon().name("yano-shadow-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("yano-shadow-timer").factory());
    }

    /** @return the shadow engines' names */
    public List<String> engineNames() {
        return engines.stream().map(LedgerValidationEngine::name).toList();
    }

    /** @return whether disagreement bundles are written (and so reads must be recorded) */
    public boolean dumpsEnabled() {
        return dumpDir != null;
    }

    /** Counts a job the caller dropped because the live-snapshot cap was reached. */
    public void recordCapRefusal() {
        droppedCap.increment();
    }

    /**
     * Counts a job the caller dropped for another reason: no snapshot could be acquired or retained (freed,
     * store unavailable), or the validation environment could not be built.
     */
    public void recordSnapshotUnavailable() {
        droppedUnavailable.increment();
    }

    /**
     * Queues a job. Ownership of {@link ShadowJob#resource()} passes to the runner, which closes it even when
     * the job is dropped.
     *
     * @return true when the job was queued
     */
    public boolean submit(ShadowJob job) {
        Objects.requireNonNull(job, "job");
        submitted.increment();
        Job running = new Job(job);
        long remaining = maxAgeMs - job.snapshotAgeMs();
        if (remaining <= 0) {
            expired.increment();
            running.release();
            return false;
        }
        inFlight.incrementAndGet();
        try {
            executor.execute(running.task);
        } catch (RejectedExecutionException e) {
            droppedQueueFull.increment();
            running.finish();
            return false;
        }
        try {
            running.timeout = timer.schedule(running::expire, remaining, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            running.expire();
        }
        return true;
    }

    /** @return a snapshot of the counters */
    public Stats stats() {
        Map<String, Map<String, Long>> byEngine = new TreeMap<>();
        disagreements.forEach((engine, rules) -> {
            Map<String, Long> byRule = new TreeMap<>();
            rules.forEach((rule, count) -> byRule.put(rule, count.sum()));
            byEngine.put(engine, Map.copyOf(byRule));
        });
        return new Stats(submitted.sum(), compared.sum(), agreements.sum(), disagreementTotal.sum(),
                Map.copyOf(byEngine), droppedCap.sum(), droppedUnavailable.sum(), droppedQueueFull.sum(), expired.sum(), engineErrors.sum(),
                dumpsWritten.sum(), dumpFailures.sum(), (int) inFlight.get());
    }

    /** @return disagreements recorded for one engine and rule label ({@code 0} when none) */
    public long disagreements(String engine, String rule) {
        Map<String, LongAdder> rules = disagreements.get(engine);
        LongAdder count = rules != null ? rules.get(rule) : null;
        return count != null ? count.sum() : 0;
    }

    @Override
    public void close() {
        // Jobs still queued never run: release their views now. Running jobs are interrupted and release
        // theirs in their own finally.
        for (Runnable queued : executor.shutdownNow()) {
            if (queued instanceof JobTask task) {
                task.job.finish();
            }
        }
        timer.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ one job

    private final class Job {
        private final ShadowJob job;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final JobTask task;
        private volatile ScheduledFuture<?> timeout;

        Job(ShadowJob job) {
            this.job = job;
            this.task = new JobTask(this);
        }

        private void run() {
            try {
                for (LedgerValidationEngine engine : engines) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    runOne(engine);
                }
            } finally {
                finish();
            }
        }

        /** Ends the job once: stops its timer, updates the in-flight gauge and releases the frozen view. */
        private void finish() {
            if (finished.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
                ScheduledFuture<?> t = timeout;
                if (t != null) {
                    t.cancel(false);
                }
            }
            release();
        }

        private void runOne(LedgerValidationEngine engine) {
            TxValidationRequest base = job.request();
            RecordingLedgerView recording = dumpDir != null ? new RecordingLedgerView(base.view()) : null;
            LedgerView view = recording != null ? recording : base.view();
            TxValidationOutcome outcome;
            try {
                outcome = engine.validate(new TxValidationRequest(base.txCbor(), view, base.env(), base.rule(),
                        base.origin(), null));
            } catch (RuntimeException e) {
                engineErrors.increment();
                log.warn("Shadow engine {} threw on tx {}: {}", engine.name(), job.txHash(), e.toString());
                return;
            }
            if (cancelled.get()) {
                return; // expired while running: discarded, not a disagreement
            }
            compared.increment();
            Optional<Verdict.Disagreement> disagreement = Verdict.compare(job.admission().verdict(),
                    Verdict.of(outcome));
            if (disagreement.isEmpty()) {
                agreements.increment();
                return;
            }
            Verdict.Disagreement d = disagreement.get();
            // Per-label first: a reader never sees the total ahead of the sum of the labels.
            disagreements.computeIfAbsent(engine.name(), ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(d.ruleLabel(), ignored -> new LongAdder()).increment();
            disagreementTotal.increment();
            if (loggedTx.putIfAbsent(job.txHash() + "|" + engine.name(), Boolean.TRUE) == null) {
                log.warn("Validation engines disagree on tx {}: {} vs shadow {}: {}", job.txHash(),
                        job.admission().engine(), engine.name(), d);
            }
            if (dumpDir != null) {
                dump(engine, outcome, recording);
            }
        }

        private void dump(LedgerValidationEngine engine, TxValidationOutcome outcome, RecordingLedgerView recording) {
            try {
                List<RecordingLedgerView.Read> reads = new ArrayList<>(job.admissionReads());
                if (recording != null) {
                    reads.addAll(recording.reads());
                }
                TxValidationRequest request = job.request();
                ValidationEnv env = request.env();
                Path file = new ShadowDumpBundle(job.txHash(), request.txCbor(), request.rule(), request.origin(), env,
                        job.admission(),
                        RecordedOutcome.of(engine.name(), outcome), reads).write(dumpDir);
                dumpsWritten.increment();
                log.info("Shadow disagreement bundle written: {}", file);
            } catch (RuntimeException e) {
                dumpFailures.increment();
                log.warn("Could not write the shadow dump for tx {}: {}", job.txHash(), e.toString());
            }
        }

        private void expire() {
            if (task.isDone() || finished.get()) {
                return;
            }
            if (cancelled.compareAndSet(false, true)) {
                expired.increment();
                task.cancel(true);
                // A queued job leaves the queue now instead of occupying it until a worker dequeues it.
                executor.remove(task);
                // A job that never started never runs its finally; one that is running sees the flag, and
                // its reads fail closed once the view is released here.
                finish();
            }
        }

        private void release() {
            if (released.compareAndSet(false, true) && job.resource() != null) {
                try {
                    job.resource().close();
                } catch (Exception e) {
                    log.debug("Releasing a shadow view failed: {}", e.toString());
                }
            }
        }
    }

    /** The queued task of a {@link Job}, so a drained queue can be released on close. */
    private static final class JobTask extends FutureTask<Void> {
        private final Job job;

        JobTask(Job job) {
            super(job::run, null);
            this.job = job;
        }
    }
}
