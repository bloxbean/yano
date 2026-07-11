package com.bloxbean.cardano.yano.runtime.apply;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.p2p.peer.PeerRecoveryReason;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Single-threaded ordered apply boundary between Yaci network callbacks and Yano's
 * durable chain/ledger mutation path.
 *
 * <p>The processor keeps Netty event-loop threads out of expensive ledger work while
 * preserving the same block order that the network delivered. Each upstream peer
 * session is represented by a generation. Data work is accepted only for the active
 * open generation. Ordinary ChainSync rollbacks are ledger data events and keep the
 * generation open; disconnects, apply failures, and recovery barriers close the
 * generation before a replacement peer session is started.</p>
 *
 * <p>The recovery contract is deliberately conservative. A caller that needs a
 * restart point must use {@link #closeGenerationAndReadRecoveryPoint(long)} so the
 * cursor read is serialized after any in-flight apply work. If an apply failure was
 * unrecoverable locally, the recovery barrier fails instead of allowing sync to
 * advance from an unsafe cursor.</p>
 */
@Slf4j
public final class LedgerApplyProcessor implements AutoCloseable {
    /**
     * Coarse lifecycle and health state for observability.
     */
    public enum State {
        UNSTARTED,
        RUNNING,
        DEGRADED,
        FAILED,
        STOPPING,
        STOPPED
    }

    /**
     * Result of one work item.
     */
    public enum Outcome {
        /**
         * A block was applied and the durable body tip may be used as a successful cursor.
         */
        APPLIED,
        /**
         * A non-block marker or control item completed successfully.
         */
        COMPLETED,
        /**
         * The item belonged to a generation that is no longer current.
         */
        SKIPPED_STALE,
        /**
         * The item was already reflected in durable state and can be treated as idempotent success.
         */
        SKIPPED_DUPLICATE,
        /**
         * The work failed. The generation is failed and recovery is requested.
         */
        FAILED
    }

    private enum GenerationState {
        OPEN,
        CLOSING,
        CLOSED,
        FAILED
    }

    private enum WorkKind {
        APPLY_BLOCK,
        BATCH_STARTED,
        BATCH_DONE,
        NO_BLOCK_FOUND,
        ROLLBACK,
        DISCONNECT,
        APPLY_FAILED,
        RECOVERY_BARRIER
    }

    /**
     * Unit of ledger apply work executed by the worker thread.
     */
    @FunctionalInterface
    public interface ApplyWork {
        /**
         * Runs the work item.
         *
         * @return the work outcome, or {@code null} to mean {@link Outcome#COMPLETED}
         * @throws Exception if the generation should fail and recovery should be requested
         */
        Outcome run() throws Exception;
    }

    /**
     * Backpressure limits for queued apply work.
     *
     * @param maxQueuedItems maximum number of data work items waiting to be applied
     * @param maxQueuedDecodedBytes approximate maximum decoded block bytes held by the data queue
     * @param reservedControlSlots capacity reserved for rollback, disconnect, and recovery barriers
     */
    public record Policy(int maxQueuedItems, long maxQueuedDecodedBytes, int reservedControlSlots) {
        public Policy {
            if (maxQueuedItems < 1) {
                throw new IllegalArgumentException("maxQueuedItems must be positive");
            }
            if (maxQueuedDecodedBytes < 1) {
                throw new IllegalArgumentException("maxQueuedDecodedBytes must be positive");
            }
            if (reservedControlSlots < 1) {
                throw new IllegalArgumentException("reservedControlSlots must be positive");
            }
        }

        /**
         * Default queue policy sized for mainnet sync while keeping control events available.
         */
        public static Policy defaults() {
            return new Policy(10_000, 256L * 1024L * 1024L, 64);
        }
    }

    /**
     * Durable point from which a replacement upstream session should resume.
     *
     * @param bodyTip last safe body tip after the closed generation has quiesced
     * @param headerTip current header tip after the same ordering barrier
     */
    public record RecoveryPoint(ChainTip bodyTip, ChainTip headerTip) {
    }

    /**
     * Snapshot of processor state for logs, health checks, and tests.
     */
    public record Status(State state,
                         long activeGeneration,
                         int dataQueueDepth,
                         int controlQueueDepth,
                         long queuedDecodedBytes,
                         String currentItem,
                         long currentItemStartedAtMillis,
                         String lastFailure) {
    }

    private record WorkItem(WorkKind kind,
                            long generation,
                            String description,
                            long estimatedBytes,
                            boolean dataItem,
                            boolean terminalForGeneration,
                            ApplyWork work,
                            CompletableFuture<Outcome> future) {
    }

    private final ChainState chainState;
    private final Consumer<PeerRecoveryReason> recoveryRequester;
    private final Policy policy;
    private final BlockingQueue<WorkItem> dataQueue;
    private final BlockingQueue<WorkItem> controlQueue;
    private final ConcurrentHashMap<Long, GenerationState> generations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Throwable> unrecoverableFailures = new ConcurrentHashMap<>();
    private final java.util.Set<Long> recoveryRequestedGenerations = ConcurrentHashMap.newKeySet();
    private final AtomicLong generationSequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final Object accountingLock = new Object();

    private volatile State state = State.UNSTARTED;
    private volatile long activeGeneration;
    private volatile long queuedDecodedBytes;
    private volatile Thread workerThread;
    private volatile WorkItem currentItem;
    private volatile long currentItemStartedAtMillis;
    private volatile String lastFailure;
    private volatile ChainTip lastSuccessfulBodyTip;

    /**
     * Creates a processor with the default queue policy.
     *
     * @param chainState durable chain state used for recovery cursor reads
     * @param recoveryRequester callback used to ask the peer supervisor for recovery
     */
    public LedgerApplyProcessor(ChainState chainState, Consumer<PeerRecoveryReason> recoveryRequester) {
        this(chainState, recoveryRequester, Policy.defaults());
    }

    /**
     * Creates a processor with an explicit queue policy.
     *
     * @param chainState durable chain state used for recovery cursor reads
     * @param recoveryRequester callback used to ask the peer supervisor for recovery
     * @param policy backpressure policy for data and control queues
     */
    public LedgerApplyProcessor(ChainState chainState,
                                Consumer<PeerRecoveryReason> recoveryRequester,
                                Policy policy) {
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.recoveryRequester = Objects.requireNonNull(recoveryRequester, "recoveryRequester");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.dataQueue = new LinkedBlockingQueue<>(policy.maxQueuedItems());
        this.controlQueue = new LinkedBlockingQueue<>(policy.reservedControlSlots());
    }

    /**
     * Starts the single apply worker. Calling this more than once is a no-op.
     */
    public void start() {
        if (closing.get()) {
            throw new IllegalStateException("LedgerApplyProcessor is closing or closed");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        state = State.RUNNING;
        workerThread = Thread.ofVirtual()
                .name("YanoLedgerApply")
                .start(this::runLoop);
    }

    /**
     * Opens a new peer-session generation and records the current durable body tip
     * as the last known safe recovery cursor for that generation.
     *
     * @return the new generation id
     */
    public long openGeneration() {
        if (closing.get()) {
            throw new IllegalStateException("LedgerApplyProcessor is closing or closed");
        }
        long generation = generationSequence.incrementAndGet();
        generations.put(generation, GenerationState.OPEN);
        activeGeneration = generation;
        lastSuccessfulBodyTip = chainState.getTip();
        if (running.get()) {
            state = State.RUNNING;
        }
        return generation;
    }

    /**
     * Returns the latest generation opened by this processor.
     */
    public long activeGeneration() {
        return activeGeneration;
    }

    /**
     * Returns whether data work may still be accepted for the generation.
     */
    public boolean isGenerationOpen(long generation) {
        return !closing.get() && generations.get(generation) == GenerationState.OPEN;
    }

    /**
     * Closes a generation and cancels queued data work for it.
     */
    public void closeGeneration(long generation) {
        closeGeneration(generation, GenerationState.CLOSED);
    }

    /**
     * Marks the generation failed from a non-worker callback and requests peer
     * recovery through the configured recovery requester.
     *
     * <p>This is used for synchronous header-apply failures. Header storage is
     * intentionally still on the network callback path, so it cannot fail through
     * an {@link WorkKind#APPLY_BLOCK} item. The failure has the same lifecycle
     * effect as a body apply failure: queued work for the generation is dropped
     * and the supervisor is asked to replace the peer from a safe cursor.</p>
     */
    public void failGenerationAndRequestRecovery(long generation, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (accountingLock) {
            if (closing.get()) {
                return;
            }
            markGenerationFailedLocked(generation, failure);
        }
        requestRecoveryOnce(generation, failure);
    }

    /**
     * Enqueues decoded block application work, using the decoded byte array length
     * for approximate memory accounting.
     */
    public CompletableFuture<Outcome> enqueueApplyBlock(long generation,
                                                        String description,
                                                        byte[] decodedBlockBytes,
                                                        ApplyWork work) {
        return enqueueApplyBlock(generation, description, estimateQueuedBytes(decodedBlockBytes), work);
    }

    /**
     * Enqueues block application work.
     *
     * <p>The returned future is cancelled if the generation is already closed. If
     * the work throws, the generation fails and peer recovery is requested.</p>
     */
    public CompletableFuture<Outcome> enqueueApplyBlock(long generation,
                                                        String description,
                                                        long estimatedBytes,
                                                        ApplyWork work) {
        WorkItem item = new WorkItem(
                WorkKind.APPLY_BLOCK,
                generation,
                description,
                Math.max(0, estimatedBytes),
                true,
                false,
                Objects.requireNonNull(work, "work"),
                new CompletableFuture<>());
        return enqueueData(item);
    }

    /**
     * Enqueues a body-fetch batch-start marker behind any prior data work.
     */
    public CompletableFuture<Outcome> enqueueBatchStarted(long generation, Runnable work) {
        return enqueueMarker(WorkKind.BATCH_STARTED, generation, "batchStarted", work);
    }

    /**
     * Enqueues a body-fetch batch-complete marker behind all blocks already queued
     * for the generation.
     */
    public CompletableFuture<Outcome> enqueueBatchDone(long generation, Runnable work) {
        return enqueueMarker(WorkKind.BATCH_DONE, generation, "batchDone", work);
    }

    /**
     * Enqueues a no-block-found marker behind any prior data work.
     */
    public CompletableFuture<Outcome> enqueueNoBlockFound(long generation, Runnable work) {
        return enqueueMarker(WorkKind.NO_BLOCK_FOUND, generation, "noBlockFound", work);
    }

    /**
     * Enqueues rollback work without closing the generation.
     *
     * <p>Rollback is queued on the data lane so it is ordered with block apply
     * items from the same generation. The caller may still wait for the returned
     * future if the upstream ChainSync callback must not process later headers
     * before the local rollback is durable.</p>
     */
    public CompletableFuture<Outcome> enqueueRollback(long generation, String description, Runnable work) {
        Objects.requireNonNull(work, "work");
        WorkItem item = new WorkItem(
                WorkKind.ROLLBACK,
                generation,
                description != null ? description : "rollback",
                0,
                true,
                false,
                () -> {
                    work.run();
                    return Outcome.COMPLETED;
                },
                new CompletableFuture<>());
        return enqueueData(item);
    }

    /**
     * Closes the generation, drops queued data for it, and enqueues rollback work
     * on the control lane so it runs after any in-flight apply item.
     */
    public CompletableFuture<Outcome> enqueueRollbackAndClose(long generation, String description, Runnable work) {
        Objects.requireNonNull(work, "work");
        if (closing.get()) {
            return cancelledFuture();
        }
        WorkItem item = new WorkItem(
                WorkKind.ROLLBACK,
                generation,
                description != null ? description : "rollback",
                0,
                false,
                true,
                () -> {
                    work.run();
                    return Outcome.COMPLETED;
                },
                new CompletableFuture<>());

        synchronized (accountingLock) {
            if (closing.get()) {
                item.future().cancel(false);
                return item.future();
            }
            GenerationState current = generations.get(generation);
            if (current == GenerationState.CLOSED) {
                return cancelledFuture();
            }
            if (current != GenerationState.FAILED) {
                generations.put(generation, GenerationState.CLOSING);
            }
            offerPromotedRollbacksLocked(dropQueuedDataForGenerationLocked(generation, true));
            offerControlLocked(item);
        }
        return item.future();
    }

    /**
     * Closes the generation, drops queued data for it, and enqueues disconnect work
     * on the control lane.
     */
    public CompletableFuture<Outcome> enqueueDisconnectAndClose(long generation, Runnable work) {
        Objects.requireNonNull(work, "work");
        if (closing.get()) {
            return cancelledFuture();
        }
        WorkItem item = new WorkItem(
                WorkKind.DISCONNECT,
                generation,
                "disconnect",
                0,
                false,
                true,
                () -> {
                    work.run();
                    return Outcome.COMPLETED;
                },
                new CompletableFuture<>());

        synchronized (accountingLock) {
            if (closing.get()) {
                item.future().cancel(false);
                return item.future();
            }
            GenerationState current = generations.get(generation);
            if (current == GenerationState.CLOSED) {
                return cancelledFuture();
            }
            if (current != GenerationState.FAILED) {
                generations.put(generation, GenerationState.CLOSING);
            }
            offerPromotedRollbacksLocked(dropQueuedDataForGenerationLocked(generation, true));
            offerControlLocked(item);
        }
        return item.future();
    }

    /**
     * Closes the generation after a disconnect without extra work.
     */
    public CompletableFuture<Outcome> enqueueDisconnectAndClose(long generation) {
        return enqueueDisconnectAndClose(generation, () -> {
        });
    }

    /**
     * Closes the generation and returns a recovery point after all accepted work
     * that can affect the durable cursor has either completed or failed.
     *
     * <p>This method must not be called from the apply worker itself. Callers should
     * wait for the returned future from a supervisor or recovery thread.</p>
     */
    public CompletableFuture<RecoveryPoint> closeGenerationAndReadRecoveryPoint(long generation) {
        if (closing.get()) {
            CompletableFuture<RecoveryPoint> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "LedgerApplyProcessor is closing or closed"));
            return failed;
        }
        if (Thread.currentThread() == workerThread) {
            CompletableFuture<RecoveryPoint> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "Recovery barrier must not be awaited from the apply worker"));
            return failed;
        }

        CompletableFuture<RecoveryPoint> recoveryPointFuture = new CompletableFuture<>();
        WorkItem item = new WorkItem(
                WorkKind.RECOVERY_BARRIER,
                generation,
                "recoveryBarrier",
                0,
                false,
                true,
                () -> {
                    Throwable unrecoverable = unrecoverableFailures.get(generation);
                    if (unrecoverable != null) {
                        throw new IllegalStateException(
                                "Ledger apply generation has an unrecoverable local failure", unrecoverable);
                    }
                    recoveryPointFuture.complete(new RecoveryPoint(recoveryBodyTipFor(generation), chainState.getHeaderTip()));
                    return Outcome.COMPLETED;
                },
                new CompletableFuture<>());

        synchronized (accountingLock) {
            if (closing.get()) {
                recoveryPointFuture.completeExceptionally(new IllegalStateException(
                        "LedgerApplyProcessor is closing or closed"));
                item.future().completeExceptionally(new IllegalStateException(
                        "LedgerApplyProcessor is closing or closed"));
                return recoveryPointFuture;
            }
            GenerationState current = generations.get(generation);
            if (current == GenerationState.OPEN) {
                generations.put(generation, GenerationState.CLOSING);
                offerPromotedRollbacksLocked(dropQueuedDataForGenerationLocked(generation, true));
            }
            if (!offerControlLocked(item)) {
                RejectedExecutionException rejection = new RejectedExecutionException(
                        "Ledger apply control queue is full");
                recoveryPointFuture.completeExceptionally(rejection);
                item.future().completeExceptionally(rejection);
                return recoveryPointFuture;
            }
        }

        item.future().whenComplete((outcome, error) -> {
            if (error != null && !recoveryPointFuture.isDone()) {
                recoveryPointFuture.completeExceptionally(error);
            } else if (outcome == Outcome.FAILED && !recoveryPointFuture.isDone()) {
                recoveryPointFuture.completeExceptionally(new IllegalStateException(
                        "Ledger apply recovery barrier failed"));
            }
        });

        return recoveryPointFuture;
    }

    /**
     * Returns a lightweight snapshot for monitoring and diagnostics.
     */
    public Status status() {
        WorkItem item = currentItem;
        return new Status(
                state,
                activeGeneration,
                dataQueue.size(),
                controlQueue.size(),
                queuedDecodedBytes,
                item != null ? item.description() : null,
                currentItemStartedAtMillis,
                lastFailure);
    }

    @Override
    public void close() {
        closeAndAwait(Duration.ofSeconds(5));
    }

    /**
     * Requests graceful shutdown and waits for the worker to stop.
     *
     * <p>This method does not interrupt in-flight apply work. It returns
     * {@code false} if the worker is still alive after the timeout, allowing the
     * caller to preserve shared resources instead of closing them underneath the
     * worker.</p>
     */
    public boolean closeAndAwait(Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        synchronized (accountingLock) {
            if (closing.compareAndSet(false, true)) {
                state = State.STOPPING;
                running.set(false);
                generations.replaceAll((ignored, current) ->
                        current == GenerationState.FAILED ? GenerationState.FAILED : GenerationState.CLOSED);
                dataQueue.forEach(item -> item.future().cancel(false));
                dataQueue.clear();
                queuedDecodedBytes = 0;
            }
        }
        Thread worker = workerThread;
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(Math.max(1L, effectiveTimeout.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                log.warn("Ledger apply worker did not stop within timeout; state remains STOPPING");
            }
        }
        synchronized (accountingLock) {
            cancelQueue(dataQueue, true);
            cancelQueue(controlQueue, false);
        }
        boolean stopped = worker == null || !worker.isAlive();
        if (stopped) {
            state = State.STOPPED;
        }
        return stopped;
    }

    /**
     * Attempts forced shutdown by interrupting the worker after cancelling queued work.
     *
     * <p>If the worker remains alive, the processor enters {@link State#FAILED} and
     * returns {@code false}. Callers must then treat ChainState and listener resources
     * as potentially still in use.</p>
     */
    public boolean forceCloseAndAwait(Duration timeout) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(5);
        synchronized (accountingLock) {
            if (closing.compareAndSet(false, true)) {
                state = State.STOPPING;
                running.set(false);
                generations.replaceAll((ignored, current) ->
                        current == GenerationState.FAILED ? GenerationState.FAILED : GenerationState.CLOSED);
                dataQueue.forEach(item -> item.future().cancel(false));
                dataQueue.clear();
                queuedDecodedBytes = 0;
            }
            cancelQueue(dataQueue, true);
            cancelQueue(controlQueue, false);
        }

        Thread worker = workerThread;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try {
                worker.join(Math.max(1L, effectiveTimeout.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                lastFailure = "Ledger apply worker did not stop after forced interrupt";
                state = State.FAILED;
                log.error("Ledger apply worker did not stop after forced interrupt; shared resources must not be closed");
                return false;
            }
        }

        state = State.STOPPED;
        return true;
    }

    private CompletableFuture<Outcome> enqueueMarker(WorkKind kind, long generation, String description, Runnable work) {
        Objects.requireNonNull(work, "work");
        WorkItem item = new WorkItem(
                kind,
                generation,
                description,
                0,
                true,
                false,
                () -> {
                    work.run();
                    return Outcome.COMPLETED;
                },
                new CompletableFuture<>());
        return enqueueData(item);
    }

    private CompletableFuture<Outcome> enqueueData(WorkItem item) {
        synchronized (accountingLock) {
            if (closing.get()) {
                item.future().cancel(false);
                return item.future();
            }
            if (!isGenerationOpen(item.generation())) {
                item.future().cancel(false);
                return item.future();
            }
            if (queuedDecodedBytes + item.estimatedBytes() > policy.maxQueuedDecodedBytes()) {
                rejectDataItem(item, "Ledger apply decoded-byte queue limit exceeded");
                return item.future();
            }
            queuedDecodedBytes += item.estimatedBytes();
            if (!dataQueue.offer(item)) {
                queuedDecodedBytes -= item.estimatedBytes();
                rejectDataItem(item, "Ledger apply queue is full");
            }
            return item.future();
        }
    }

    private void rejectDataItem(WorkItem item, String message) {
        RejectedExecutionException rejection = new RejectedExecutionException(message);
        item.future().completeExceptionally(rejection);
        scheduleGenerationFailure(item.generation(), rejection);
    }

    private CompletableFuture<Outcome> enqueueControl(WorkItem item) {
        synchronized (accountingLock) {
            offerControlLocked(item);
        }
        return item.future();
    }

    private void runLoop() {
        try {
            while (running.get() || !controlQueue.isEmpty() || !dataQueue.isEmpty()) {
                WorkItem item = pollNextItem();
                if (item == null) {
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }
                process(item);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            currentItem = null;
            currentItemStartedAtMillis = 0;
            if (closing.get()) {
                state = State.STOPPED;
            } else if (state != State.STOPPING) {
                state = State.STOPPED;
            }
        }
    }

    private WorkItem pollNextItem() {
        synchronized (accountingLock) {
            WorkItem item = controlQueue.poll();
            if (item == null) {
                item = dataQueue.poll();
            }
            if (item != null && item.dataItem()) {
                queuedDecodedBytes -= item.estimatedBytes();
                if (queuedDecodedBytes < 0) {
                    queuedDecodedBytes = 0;
                }
            }
            return item;
        }
    }

    private void process(WorkItem item) {
        if (!shouldProcess(item)) {
            item.future().cancel(false);
            return;
        }

        currentItem = item;
        currentItemStartedAtMillis = System.currentTimeMillis();
        try {
            Outcome outcome = item.work().run();
            Outcome effectiveOutcome = outcome != null ? outcome : Outcome.COMPLETED;
            if (effectiveOutcome == Outcome.FAILED) {
                IllegalStateException failure = new IllegalStateException(
                        "Ledger apply work returned FAILED: generation=" + item.generation()
                                + ", kind=" + item.kind()
                                + ", description=" + item.description());
                item.future().complete(Outcome.FAILED);
                failGeneration(item.generation(), failure);
                return;
            }
            recordSuccessfulCursor(item, effectiveOutcome);
            item.future().complete(effectiveOutcome);
            if (item.terminalForGeneration()) {
                generations.put(item.generation(), GenerationState.CLOSED);
            }
        } catch (Throwable t) {
            lastFailure = t.toString();
            item.future().complete(Outcome.FAILED);
            failGeneration(item.generation(), t);
        } finally {
            currentItem = null;
            currentItemStartedAtMillis = 0;
        }
    }

    private boolean shouldProcess(WorkItem item) {
        if (item.kind() == WorkKind.RECOVERY_BARRIER) {
            return true;
        }
        if (item.kind() == WorkKind.APPLY_FAILED) {
            return true;
        }
        GenerationState generationState = generations.get(item.generation());
        if (item.kind() == WorkKind.ROLLBACK) {
            return generationState == GenerationState.CLOSING
                    || generationState == GenerationState.OPEN
                    || generationState == GenerationState.FAILED;
        }
        if (item.kind() == WorkKind.DISCONNECT && item.terminalForGeneration()) {
            return generationState == GenerationState.CLOSING
                    || generationState == GenerationState.OPEN
                    || generationState == GenerationState.FAILED;
        }
        return generationState == GenerationState.OPEN;
    }

    private void recordSuccessfulCursor(WorkItem item, Outcome outcome) {
        if (item.kind() == WorkKind.APPLY_BLOCK && outcome == Outcome.APPLIED) {
            lastSuccessfulBodyTip = chainState.getTip();
            return;
        }
        if (item.kind() == WorkKind.ROLLBACK && outcome == Outcome.COMPLETED) {
            lastSuccessfulBodyTip = chainState.getTip();
            return;
        }
        if (item.terminalForGeneration() && outcome == Outcome.COMPLETED) {
            lastSuccessfulBodyTip = chainState.getTip();
        }
    }

    private ChainTip recoveryBodyTipFor(long generation) {
        GenerationState generationState = generations.get(generation);
        if (generationState == GenerationState.FAILED) {
            return lastSuccessfulBodyTip;
        }
        return chainState.getTip();
    }

    private void scheduleGenerationFailure(long generation, Throwable failure) {
        WorkItem item = new WorkItem(
                WorkKind.APPLY_FAILED,
                generation,
                "applyFailed",
                0,
                false,
                false,
                () -> {
                    requestRecoveryOnce(generation, failure);
                    return Outcome.COMPLETED;
                },
                new CompletableFuture<>());
        synchronized (accountingLock) {
            markGenerationFailedLocked(generation, failure);
            if (!offerControlLocked(item)) {
                requestRecoveryOnce(generation, failure);
            }
        }
    }

    private boolean offerControlLocked(WorkItem item) {
        WorkItem itemToOffer = compactControlQueueFor(item);
        if (itemToOffer == null) {
            return true;
        }
        if (controlQueue.offer(itemToOffer)) {
            return true;
        }

        RejectedExecutionException rejection = new RejectedExecutionException(
                "Ledger apply control queue is full");
        item.future().completeExceptionally(rejection);
        log.warn("Ledger apply control queue is full; rejecting control item: generation={}, kind={}, description={}",
                item.generation(), item.kind(), item.description());
        return false;
    }

    private WorkItem compactControlQueueFor(WorkItem item) {
        List<WorkItem> displaced = new ArrayList<>();
        if (item.kind() == WorkKind.RECOVERY_BARRIER) {
            controlQueue.removeIf(existing -> removeSameGenerationControl(existing, item, displaced));
            return displaced.isEmpty() ? item : foldControlsInto(item, displaced);
        }

        if (item.kind() == WorkKind.ROLLBACK) {
            List<WorkItem> after = new ArrayList<>();
            controlQueue.removeIf(existing -> {
                if (existing.generation() == item.generation()
                        && (existing.kind() == WorkKind.APPLY_FAILED
                        || existing.kind() == WorkKind.RECOVERY_BARRIER)) {
                    after.add(existing);
                    return true;
                }
                if (existing.generation() == item.generation() && existing.kind() == WorkKind.DISCONNECT) {
                    displaced.add(existing);
                    return true;
                }
                return false;
            });
            return displaced.isEmpty() && after.isEmpty() ? item : foldControlsAround(item, displaced, after);
        }

        if (item.kind() == WorkKind.DISCONNECT) {
            boolean superseded = controlQueue.stream().anyMatch(existing ->
                    existing.generation() == item.generation()
                            && (existing.kind() == WorkKind.DISCONNECT
                            || existing.kind() == WorkKind.RECOVERY_BARRIER
                            || (existing.kind() == WorkKind.ROLLBACK && controlQueue.remainingCapacity() == 0)));
            if (superseded) {
                item.future().complete(Outcome.COMPLETED);
                return null;
            }
        }

        if (item.kind() == WorkKind.APPLY_FAILED) {
            boolean superseded = controlQueue.stream().anyMatch(existing ->
                    existing.generation() == item.generation()
                            && (existing.kind() == WorkKind.DISCONNECT
                            || existing.kind() == WorkKind.RECOVERY_BARRIER));
            if (superseded) {
                item.future().complete(Outcome.COMPLETED);
                return null;
            }
        }

        return item;
    }

    private boolean removeSameGenerationControl(WorkItem existing, WorkItem item, List<WorkItem> displaced) {
        if (existing.generation() == item.generation() && existing.kind() != WorkKind.RECOVERY_BARRIER) {
            displaced.add(existing);
            return true;
        }
        return false;
    }

    private WorkItem foldControlsInto(WorkItem item, List<WorkItem> controls) {
        return foldControlsAround(item, controls, List.of());
    }

    private WorkItem foldControlsAround(WorkItem item, List<WorkItem> before, List<WorkItem> after) {
        boolean terminalForGeneration = item.terminalForGeneration()
                || before.stream().anyMatch(WorkItem::terminalForGeneration)
                || after.stream().anyMatch(WorkItem::terminalForGeneration);
        return new WorkItem(
                item.kind(),
                item.generation(),
                item.description(),
                item.estimatedBytes(),
                item.dataItem(),
                terminalForGeneration,
                () -> {
                    runFoldedControls(item, before);
                    Outcome itemOutcome;
                    try {
                        itemOutcome = item.work().run();
                    } catch (Throwable t) {
                        completeFoldedControlsExceptionally(after, t);
                        throw asException(t);
                    }
                    recordSuccessfulCursor(item, itemOutcome != null ? itemOutcome : Outcome.COMPLETED);
                    runFoldedControls(item, after);
                    return itemOutcome;
                },
                item.future());
    }

    private void runFoldedControls(WorkItem item, List<WorkItem> controls) throws Exception {
        for (int i = 0; i < controls.size(); i++) {
            WorkItem control = controls.get(i);
            try {
                Outcome outcome = control.work().run();
                if (outcome == Outcome.FAILED) {
                    throw new IllegalStateException("Folded ledger apply control returned FAILED");
                }
                Outcome effectiveOutcome = outcome != null ? outcome : Outcome.COMPLETED;
                recordSuccessfulCursor(control, effectiveOutcome);
                control.future().complete(effectiveOutcome);
            } catch (Throwable t) {
                control.future().completeExceptionally(t);
                completeFoldedControlsExceptionally(controls.subList(i + 1, controls.size()), t);
                log.warn("Ledger apply control item failed while folding into {}: generation={}, kind={}, error={}",
                        item.kind(), control.generation(), control.kind(), t.toString(), t);
                throw asException(t);
            }
        }
    }

    private void completeFoldedControlsExceptionally(List<WorkItem> controls, Throwable t) {
        for (WorkItem control : controls) {
            control.future().completeExceptionally(t);
        }
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Exception e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new RuntimeException(t);
    }

    private void failGeneration(long generation, Throwable failure) {
        synchronized (accountingLock) {
            if (isUnrecoverableApplyFailure(failure)) {
                unrecoverableFailures.put(generation, failure);
            }
            markGenerationFailedLocked(generation, failure);
        }
        requestRecoveryOnce(generation, failure);
    }

    private void markGenerationFailedLocked(long generation, Throwable failure) {
        log.warn("Ledger apply generation failed: generation={}, error={}",
                generation, failure.toString(), failure);
        if (!closing.get()) {
            state = isUnrecoverableApplyFailure(failure) ? State.DEGRADED : State.FAILED;
        }
        lastFailure = failure.toString();
        generations.put(generation, GenerationState.FAILED);
        offerPromotedRollbacksLocked(dropQueuedDataForGenerationLocked(generation, true));
        dropQueuedControlForGenerationLocked(generation);
    }

    private static boolean isUnrecoverableApplyFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnrecoverableApplyException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requestRecoveryOnce(long generation, Throwable failure) {
        if (closing.get()) {
            return;
        }
        if (!recoveryRequestedGenerations.add(generation)) {
            return;
        }
        try {
            recoveryRequester.accept(PeerRecoveryReason.APPLY_FAILED);
        } catch (RuntimeException e) {
            log.warn("Ledger apply recovery request failed: generation={}, error={}",
                    generation, e.toString(), e);
        }
    }

    private void closeGeneration(long generation, GenerationState targetState) {
        synchronized (accountingLock) {
            GenerationState current = generations.get(generation);
            if (current == null || current == GenerationState.FAILED) {
                return;
            }
            if (current == GenerationState.CLOSING && targetState == GenerationState.CLOSED) {
                return;
            }
            generations.put(generation, targetState);
            dropQueuedDataForGenerationLocked(generation);
        }
    }

    private void dropQueuedWorkForGeneration(long generation, boolean includeControl) {
        synchronized (accountingLock) {
            dropQueuedDataForGenerationLocked(generation);
            if (includeControl) {
                dropQueuedControlForGenerationLocked(generation);
            }
        }
    }

    private void dropQueuedControlForGenerationLocked(long generation) {
        controlQueue.removeIf(item -> {
            if (item.generation() == generation
                    && item.kind() != WorkKind.RECOVERY_BARRIER
                    && item.kind() != WorkKind.ROLLBACK
                    && item.kind() != WorkKind.DISCONNECT) {
                item.future().cancel(false);
                return true;
            }
            return false;
        });
    }

    private void dropQueuedDataForGenerationLocked(long generation) {
        dropQueuedDataForGenerationLocked(generation, false);
    }

    private List<WorkItem> dropQueuedDataForGenerationLocked(long generation, boolean preserveRollbacks) {
        List<WorkItem> rollbacks = new ArrayList<>();
        dataQueue.removeIf(item -> {
            if (item.generation() != generation) {
                return false;
            }
            queuedDecodedBytes -= item.estimatedBytes();
            if (preserveRollbacks && item.kind() == WorkKind.ROLLBACK) {
                rollbacks.add(asControlRollback(item));
                return true;
            }
            item.future().cancel(false);
            return true;
        });
        if (queuedDecodedBytes < 0) {
            queuedDecodedBytes = 0;
        }
        return rollbacks;
    }

    private WorkItem asControlRollback(WorkItem item) {
        return new WorkItem(
                item.kind(),
                item.generation(),
                item.description(),
                0,
                false,
                item.terminalForGeneration(),
                item.work(),
                item.future());
    }

    private void offerPromotedRollbacksLocked(List<WorkItem> rollbacks) {
        for (WorkItem rollback : rollbacks) {
            offerControlLocked(rollback);
        }
    }

    private void releaseQueuedBytes(long bytes) {
        if (bytes <= 0) {
            return;
        }
        synchronized (accountingLock) {
            queuedDecodedBytes -= bytes;
            if (queuedDecodedBytes < 0) {
                queuedDecodedBytes = 0;
            }
        }
    }

    private void cancelQueue(BlockingQueue<WorkItem> queue, boolean releaseBytes) {
        WorkItem item;
        while ((item = queue.poll()) != null) {
            if (releaseBytes) {
                releaseQueuedBytes(item.estimatedBytes());
            }
            item.future().cancel(false);
        }
    }

    private static <T> CompletableFuture<T> cancelledFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.cancel(false);
        return future;
    }

    /**
     * Estimates the queue memory cost for decoded block bytes.
     */
    public static long estimateQueuedBytes(byte[] bytes) {
        return bytes != null ? bytes.length : 0;
    }
}
