package org.yanoproject.runtime.validation.shadowsync;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import lombok.extern.slf4j.Slf4j;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.runtime.validation.ValidationEnvFactory;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncReport.BlockRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Shadow sync (ADR-056 §7, §8, Phase 7a): validates every transaction of every applied Conway block (protocol version
 * 9 and later) with the configured engines, against the block's pre-block state, and reports where an engine would
 * not have accepted the chain. Observe only: it never changes what the node applies.
 *
 * <h2>Capture point</h2>
 * A {@code BlockAppliedEvent} listener registered ahead of every other ({@link Integer#MIN_VALUE}). The event is
 * delivered synchronously on the apply thread inside the block's canonical write section (the follower's
 * {@code BodyFetchManager.applyBlock}: store the block, run the epoch boundary events, then {@code BlockAppliedEvent},
 * UTxO at order 100 and account state at 110; a producer: boundary section, then a store section publishing
 * {@code BlockAppliedEvent}). So when this listener runs, the epoch boundary before the block is committed and none of
 * the block's own changes are: {@link PreBlockState.Source#capture} takes exactly the pre-block state
 * ({@code CanonicalStateGate.captureInWriteSection}).
 *
 * <h2>Throughput and backpressure</h2>
 * The apply thread only captures (a RocksDB snapshot plus a small copy) and starts a virtual thread for the block once
 * it holds one of {@code shadow-sync-max-in-flight} permits (default half the processors). So at most that many
 * blocks, each holding its own snapshot, validate at once; when all permits are taken the apply thread waits, so sync
 * slows to validation speed, up to {@code shadow-sync-max-wait-ms} (default 30 s), after which the block is skipped
 * and reported. Validation never takes the canonical gate, so waiting inside the write section cannot deadlock. Blocks
 * validate in parallel; the transactions of one block sequentially over its overlay. Validation is CPU-bound and
 * shares the virtual-thread carriers with the node's own sync pipeline, which is why the bound stays below the
 * carrier count (see {@link ShadowSyncSettings#maxInFlight()}).
 *
 * <p><b>The wait holds the canonical write lock.</b> It happens inside the block's write section, so while the apply
 * thread waits every snapshot acquisition waits too: mempool admission and rebuilds (engine-API admission), block
 * selection, admission shadows and anything else reading a canonical snapshot; the published tip does not move and
 * chain sync buffers upstream. Block application itself is the point of the wait. The bound is short so that a stuck
 * or pathologically slow engine delays the node by at most that long per block, then costs coverage (a reported
 * skipped block) rather than availability. A healthy engine frees a slot within one block's validation time (the
 * fastest of {@code max-in-flight} blocks), far below 30 s.</p>
 *
 * <h2>Stopping</h2>
 * {@link #close()} stops taking blocks, then lets every block already handed over finish (up to
 * {@link #CLOSE_TIMEOUT_SECONDS}), so a restart leaves no coverage gap. Blocks still validating after that are reported
 * as skipped at stop, and their results, which may read a closed database, are discarded.
 */
@Slf4j
public final class ShadowSyncValidator implements AutoCloseable {

    /**
     * The {@code BlockAppliedEvent} priority of the capture listener: ahead of every other listener, so the captured
     * state holds none of the block's own changes (UTxO applies at 100, account state and governance at 110).
     */
    public static final int SUBSCRIPTION_PRIORITY = Integer.MIN_VALUE;

    /** The first Conway protocol major version. */
    public static final int FIRST_CONWAY_MAJOR = 9;

    /** How long {@link #close()} waits for the blocks already handed over to finish. */
    public static final long CLOSE_TIMEOUT_SECONDS = 120;

    /**
     * Counters of the validator itself (the rest is in {@link ShadowSyncReport.Stats}).
     *
     * @param inFlight   blocks captured and not yet finished (each on its own virtual thread)
     * @param maxInFlight the bound
     * @param outsideWriteSection {@code BlockAppliedEvent}s delivered outside a write section (not validated)
     */
    public record Status(int inFlight, int maxInFlight, long outsideWriteSection, List<String> engines,
                         ShadowSyncReport.Stats report) {
    }

    private final ShadowSyncSettings settings;
    private final List<LedgerValidationEngine> engines;
    private final ValidationEnvFactory envFactory;
    private final PreBlockState.Source states;
    private final Function<String, byte[]> storedBlock;
    private final BooleanSupplier inWriteSection;
    private final ShadowSyncReport report;
    private final SyncBlockValidator validator = new SyncBlockValidator();
    private final Semaphore permits;
    private final ExecutorService workers;
    private final ScheduledExecutorService summaries;
    private final Set<Job> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger outsideWriteSection = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile SubscriptionHandle subscription;

    /**
     * @param engines        the engines (owned by the caller)
     * @param envFactory     builds the environment at the block's slot from the pre-block view
     * @param states         captures the pre-block state on the apply thread
     * @param storedBlock    the stored bytes of a block by hash (hex), used when the event carries no CBOR
     * @param inWriteSection true when the calling thread is inside a canonical write section
     * @param report         where results go (closed by {@link #close()})
     */
    public ShadowSyncValidator(ShadowSyncSettings settings, List<LedgerValidationEngine> engines,
                               ValidationEnvFactory envFactory, PreBlockState.Source states,
                               Function<String, byte[]> storedBlock, BooleanSupplier inWriteSection,
                               ShadowSyncReport report) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.engines = List.copyOf(engines);
        if (this.engines.isEmpty()) {
            throw new IllegalArgumentException("shadow sync needs an engine");
        }
        this.envFactory = Objects.requireNonNull(envFactory, "envFactory");
        this.states = Objects.requireNonNull(states, "states");
        this.storedBlock = Objects.requireNonNull(storedBlock, "storedBlock");
        this.inWriteSection = Objects.requireNonNull(inWriteSection, "inWriteSection");
        this.report = Objects.requireNonNull(report, "report");
        this.permits = new Semaphore(settings.maxInFlight());
        // One virtual thread per block, started only after its permit was taken: the permits bound the parallelism.
        this.workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("yano-shadow-sync-", 0).factory());
        this.summaries = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("yano-shadow-sync-summary").factory());
        if (settings.summarySeconds() > 0) {
            summaries.scheduleAtFixedRate(this::logSummary, settings.summarySeconds(), settings.summarySeconds(),
                    TimeUnit.SECONDS);
        }
    }

    /** Subscribes to {@code BlockAppliedEvent} ahead of every other listener. */
    public synchronized void attach(EventBus eventBus) {
        if (subscription != null) {
            return;
        }
        subscription = eventBus.subscribe(BlockAppliedEvent.class, ctx -> onBlockApplied(ctx.event()),
                SubscriptionOptions.builder().priority(SUBSCRIPTION_PRIORITY).build());
        log.info("Shadow sync on (ADR-056 Phase 7a): engines={}, max-in-flight={} (virtual threads, {} carriers), "
                        + "report={}, dumps={}",
                engines.stream().map(LedgerValidationEngine::name).toList(), settings.maxInFlight(),
                ShadowSyncSettings.carriers(),
                settings.reportFile() != null ? settings.reportFile() : "none",
                settings.dumpDir() != null ? settings.dumpDir() : "none");
    }

    /**
     * Handles one applied block on the apply thread. Never throws: shadow sync must not affect block application.
     */
    public void onBlockApplied(BlockAppliedEvent event) {
        try {
            handle(event);
        } catch (RuntimeException | Error e) {
            if (e instanceof VirtualMachineError vme) {
                throw vme;
            }
            log.warn("Shadow sync: handling block {} failed: {}", event != null ? event.blockHash() : null,
                    e.toString());
        }
    }

    private void handle(BlockAppliedEvent event) {
        if (closing.get() || event == null) {
            return;
        }
        Block block = event.block();
        if (block == null) {
            report.skippedPreConway(0); // Byron: the event carries no Shelley-family block
            return;
        }
        List<TransactionBody> bodies = block.getTransactionBodies() != null ? block.getTransactionBodies() : List.of();
        if (event.era() != Era.Conway) {
            report.skippedPreConway(bodies.size());
            return;
        }
        if (bodies.isEmpty()) {
            report.emptyBlock();
            return;
        }
        BlockRef ref = new BlockRef(event.slot(), event.blockNumber(), event.blockHash());
        if (!inWriteSection.getAsBoolean()) {
            // Not applied through a canonical write section: no pre-block state can be captured.
            outsideWriteSection.incrementAndGet();
            report.failedBeforeSubmit(ref, bodies.size(), "block applied outside a canonical write section");
            return;
        }
        if (!acquirePermit(ref, bodies.size())) {
            return;
        }
        Lookup<PreBlockState> captured;
        try {
            captured = states.capture(event.slot());
        } catch (RuntimeException e) {
            permits.release();
            report.failedBeforeSubmit(ref, bodies.size(), "pre-block state capture failed: " + e);
            return;
        }
        if (!(captured instanceof Lookup.Present<PreBlockState> present)) {
            permits.release();
            report.failedBeforeSubmit(ref, bodies.size(), "pre-block state unavailable: "
                    + (captured instanceof Lookup.Unavailable<PreBlockState> u ? u.reason() : "absent"));
            return;
        }
        List<String> appliedIds = new ArrayList<>(bodies.size());
        bodies.forEach(body -> appliedIds.add(body.getTxHash()));
        Job job = new Job(ref, block.getCbor(), appliedIds, present.value());
        inFlight.add(job);
        report.submitted();
        try {
            workers.execute(job);
        } catch (RejectedExecutionException e) {
            // close() began after the check above. Unreachable in the node (sync stops before shadow sync closes);
            // if it happens after close() closed the report, only the block's JSONL line is lost, not its counts.
            if (job.settle()) {
                report.skippedAtStop(ref, bodies.size(), "skipped: shadow sync is stopping");
            }
            finish(job);
        }
    }

    private boolean acquirePermit(BlockRef ref, int txCount) {
        if (permits.tryAcquire()) {
            return true;
        }
        long started = System.nanoTime();
        try {
            boolean acquired = permits.tryAcquire(settings.maxWaitMs(), TimeUnit.MILLISECONDS);
            report.backpressureWait(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (!acquired) {
                report.failedBeforeSubmit(ref, txCount, "skipped: no free shadow-sync slot within "
                        + settings.maxWaitMs() + " ms (validation is not keeping up)");
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.failedBeforeSubmit(ref, txCount, "skipped: interrupted while waiting for a shadow-sync slot");
            return false;
        }
    }

    /**
     * Validates one block on its virtual thread. Every outcome is recorded only if {@link Job#settle()} succeeds: once
     * {@link #close()} gave up on the block and reported it skipped, its result is discarded.
     */
    private void validate(Job job) {
        BlockRef ref = job.ref;
        int txCount = job.appliedIds.size();
        try {
            byte[] bytes = job.cbor != null && !job.cbor.isEmpty() ? HexUtil.decodeHexString(job.cbor)
                    : storedBlock.apply(ref.blockHash());
            if (bytes == null) {
                failed(job, txCount, "the block bytes are not available");
                return;
            }
            SyncBlock block;
            try {
                block = SyncBlock.parse(bytes);
            } catch (IllegalArgumentException e) {
                failed(job, txCount, e.getMessage());
                return;
            }
            if (!block.txIds().equals(job.appliedIds)) {
                report.idMismatch(ref, block.txIds(), job.appliedIds);
            }
            LedgerView view;
            ValidationEnv env;
            try {
                view = job.state.view();
                env = envFactory.create(ref.slot(), view).withForecastBasisSlot(job.state.forecastBasisSlot());
            } catch (RuntimeException e) {
                failed(job, block.size(), "no pre-block view or environment: " + e.getMessage());
                return;
            }
            if (env.protocolMajor() < FIRST_CONWAY_MAJOR) {
                if (job.settle()) {
                    report.preConwayAfterCapture(block.size());
                }
                return;
            }
            long started = System.nanoTime();
            SyncBlockValidator.BlockResult result = validator.validate(block, view, env, engines, report.dumper());
            if (job.settle()) {
                int parentEpoch = job.state.parentEpoch();
                report.record(ref, (int) env.currentEpoch(), parentEpoch >= 0 && parentEpoch < env.currentEpoch(),
                        result, System.nanoTime() - started);
            }
        } catch (RuntimeException | LinkageError e) {
            failed(job, txCount, "shadow sync failed: " + e);
        } finally {
            finish(job);
        }
    }

    private void failed(Job job, int txCount, String reason) {
        if (job.settle()) {
            report.failedAfterSubmit(job.ref, txCount, reason);
        }
    }

    private void finish(Job job) {
        try {
            job.state.close();
        } catch (RuntimeException e) {
            log.debug("Releasing a pre-block state failed: {}", e.toString());
        } finally {
            inFlight.remove(job);
            permits.release();
        }
    }

    /**
     * Waits until every block handed over so far is validated.
     *
     * @return true when idle within the timeout
     */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!inFlight.isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /** One block handed to validation; it owns {@link #state} until {@link #finish} releases it. */
    private final class Job implements Runnable {
        private final BlockRef ref;
        private final String cbor;
        private final List<String> appliedIds;
        private final PreBlockState state;
        private final AtomicBoolean settled = new AtomicBoolean();

        Job(BlockRef ref, String cbor, List<String> appliedIds, PreBlockState state) {
            this.ref = ref;
            this.cbor = cbor;
            this.appliedIds = appliedIds;
            this.state = state;
        }

        /** @return true for the one caller that records the block's outcome: the job itself, or a timed-out close */
        boolean settle() {
            return settled.compareAndSet(false, true);
        }

        @Override
        public void run() {
            validate(this);
        }
    }

    public Status status() {
        return new Status(inFlight.size(), settings.maxInFlight(), outsideWriteSection.get(),
                engines.stream().map(LedgerValidationEngine::name).toList(), report.stats());
    }

    public ShadowSyncReport report() {
        return report;
    }

    /** @return the free permits (tests: every path returns its permit) */
    int availablePermits() {
        return permits.availablePermits();
    }

    private void logSummary() {
        try {
            log.info("Shadow sync summary (in flight {}): {}", inFlight.size(), ShadowSyncReport.summary(report.stats()));
        } catch (RuntimeException e) {
            log.debug("Shadow sync summary failed: {}", e.toString());
        }
    }

    /**
     * Stops taking blocks, lets every block already handed over finish (up to {@link #CLOSE_TIMEOUT_SECONDS}), reports
     * the ones still validating after that as skipped at stop, logs the final summary and closes the report. Call
     * before the engines and the database close.
     */
    @Override
    public void close() {
        close(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    void close(long timeout, TimeUnit unit) {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        SubscriptionHandle handle = subscription;
        if (handle != null) {
            handle.close();
        }
        summaries.shutdownNow();
        workers.shutdown();
        String gaveUp = null;
        try {
            if (!workers.awaitTermination(timeout, unit)) {
                gaveUp = "still validating " + unit.toMillis(timeout) + " ms after shadow sync began to stop";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            gaveUp = "interrupted while shadow sync waited for it to finish";
        }
        if (gaveUp != null) {
            int skipped = 0;
            for (Job job : List.copyOf(inFlight)) {
                if (job.settle()) {
                    skipped++;
                    report.skippedAtStop(job.ref, job.appliedIds.size(), "skipped: " + gaveUp);
                }
            }
            workers.shutdownNow();
            // A block still running keeps its snapshot until it returns; the database invalidates it when it closes.
            log.warn("Shadow sync: {} blocks were not validated: {}", skipped, gaveUp);
        }
        try {
            // Starts with the coverage counts (ShadowSyncReport, "Coverage"): any failed block is a gap.
            log.info("Shadow sync stopped: {}", ShadowSyncReport.summary(report.stats()));
        } catch (RuntimeException e) {
            log.debug("Shadow sync summary failed: {}", e.toString());
        }
        try {
            report.close();
        } catch (RuntimeException e) {
            // Never let the report keep the caller from closing the engines and the rest of the node.
            log.warn("Shadow sync: closing the report failed: {}", e.toString());
        }
    }
}
