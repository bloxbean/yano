package org.yanoproject.ledger.amaru.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Module instances, each owned by its own dedicated worker thread (ADR-057 §2, "Instances" and
 * "Timeouts").
 *
 * <ul>
 *   <li>A caller waits at most {@code timeout} for an idle worker ({@link AmaruEngineException.Kind#BUSY}
 *       otherwise), then at most {@code timeout} for the call itself.</li>
 *   <li>A call that throws (a wasm trap, {@code proc_exit}, a stack overflow, a malformed response
 *       buffer) discards that worker's instance; the worker creates a fresh one for its next call.</li>
 *   <li>A call that times out <em>poisons</em> its worker: the worker thread is interrupted and a new
 *       worker takes its place. Endive checks the thread's interrupt flag on every call and backward
 *       branch, in AOT-compiled code as in the interpreter, and throws
 *       {@code WasmInterruptedException}, so a looping guest stops and its thread ends. A worker that is
 *       still running {@link #ABANDON_GRACE} after the interrupt counts as <em>abandoned</em> (it would
 *       have to be stuck outside wasm code, where Endive cannot see the flag).</li>
 *   <li>Once {@code maxAbandoned} workers have been abandoned, the pool is <em>unhealthy</em> until the
 *       node restarts: every call fails at once with {@link AmaruEngineException.Kind#UNHEALTHY}.</li>
 * </ul>
 */
public final class AmaruInstancePool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AmaruInstancePool.class);

    /** How long a poisoned worker may take to stop before it counts as abandoned. */
    public static final Duration ABANDON_GRACE = Duration.ofMillis(250);

    private final Supplier<? extends AmaruInstance> factory;
    private final Duration timeout;
    private final int maxAbandoned;
    private final long stackSize;
    private final BlockingQueue<Worker> idle = new LinkedBlockingQueue<>();
    private final List<Thread> abandonedThreads = new ArrayList<>();
    private final AtomicInteger workerIds = new AtomicInteger();
    private final AtomicInteger timeouts = new AtomicInteger();
    private volatile boolean unhealthy;
    private volatile boolean closed;

    /**
     * @param factory      creates an instance; called on the worker thread that will own it
     * @param size         number of workers
     * @param timeout      the per-call wait
     * @param maxAbandoned abandoned workers tolerated before the pool turns unhealthy
     * @param stackSize    each worker thread's stack size in bytes (0 = JVM default)
     */
    public AmaruInstancePool(Supplier<? extends AmaruInstance> factory, int size, Duration timeout,
                             int maxAbandoned, long stackSize) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxAbandoned = maxAbandoned;
        this.stackSize = stackSize;
        for (int i = 0; i < size; i++) {
            idle.add(new Worker());
        }
    }

    /** @return false once the abandoned-worker cap was reached; stays false until restart */
    public boolean isHealthy() {
        return !unhealthy && !closed;
    }

    /** @return workers abandoned so far */
    public synchronized int abandonedCount() {
        return abandonedThreads.size();
    }

    /** @return calls that timed out so far */
    public int timeoutCount() {
        return timeouts.get();
    }

    /**
     * Runs {@code work} on an idle worker's instance.
     *
     * @throws AmaruEngineException when the call traps, times out, finds no free worker in time, or the
     *                              pool is unhealthy
     */
    public <T> T call(Function<AmaruInstance, T> work) {
        if (!isHealthy()) {
            throw new AmaruEngineException(AmaruEngineException.Kind.UNHEALTHY, closed
                    ? "the Amaru engine is closed"
                    : "the Amaru engine is unhealthy: " + abandonedCount() + " stuck call(s) reached max-abandoned "
                    + maxAbandoned + "; restart the node");
        }
        Worker worker;
        try {
            worker = idle.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmaruEngineException(AmaruEngineException.Kind.BUSY, "interrupted while waiting for an instance", e);
        }
        if (worker == null) {
            throw new AmaruEngineException(AmaruEngineException.Kind.BUSY,
                    "no Amaru instance became free within " + timeout.toMillis() + " ms");
        }
        // The timeout covers the call itself, not the wait for a free worker.
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<T> result = worker.submit(work, deadline);
        if (result == null) {
            replace(worker, "did not accept a call");
            throw new AmaruEngineException(AmaruEngineException.Kind.BUSY,
                    "the Amaru worker " + worker.name() + " did not accept the call");
        }
        try {
            T value = result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            release(worker);
            return value;
        } catch (ExecutionException e) {
            release(worker);
            throw classify(e.getCause());
        } catch (TimeoutException e) {
            timeouts.incrementAndGet();
            replace(worker, "timed out after " + timeout.toMillis() + " ms");
            throw new AmaruEngineException(AmaruEngineException.Kind.TIMEOUT,
                    "the Amaru module did not answer within " + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            replace(worker, "caller interrupted");
            throw new AmaruEngineException(AmaruEngineException.Kind.TIMEOUT, "interrupted while waiting for the module", e);
        }
    }

    /** Returns a worker to the idle set, or stops it when the pool was closed while it was busy. */
    private void release(Worker worker) {
        idle.add(worker);
        if (closed && idle.remove(worker)) {
            worker.poison();
        }
    }

    private static AmaruEngineException classify(Throwable cause) {
        if (cause instanceof AmaruEngineException engine) {
            return engine;
        }
        return new AmaruEngineException(AmaruEngineException.Kind.TRAP,
                "the Amaru module trapped: " + cause, cause);
    }

    /** Poisons a worker, waits briefly for it to stop, and puts a fresh worker in its place. */
    private void replace(Worker worker, String reason) {
        worker.poison();
        if (!closed) {
            idle.add(new Worker());
        }
        boolean stopped;
        try {
            stopped = worker.awaitTermination(ABANDON_GRACE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = false;
        }
        if (stopped) {
            log.warn("Amaru instance {} {}; its thread stopped on interrupt and was replaced", worker.name(), reason);
            return;
        }
        int count;
        synchronized (this) {
            abandonedThreads.add(worker.thread);
            count = abandonedThreads.size();
            if (count >= maxAbandoned) {
                unhealthy = true;
            }
        }
        log.error("Amaru instance {} {} and did not stop on interrupt: abandoned ({} of max-abandoned {}){}",
                worker.name(), reason, count, maxAbandoned,
                unhealthy ? "; the Amaru engine is now UNHEALTHY and fails closed until restart" : "");
    }

    /** Stops idle workers now and busy workers when their call returns. */
    @Override
    public void close() {
        closed = true;
        Worker worker;
        while ((worker = idle.poll()) != null) {
            worker.poison();
        }
    }

    private final class Worker implements Runnable {
        private final SynchronousQueue<Task<?>> tasks = new SynchronousQueue<>();
        private final Thread thread;
        private volatile boolean poisoned;
        private AmaruInstance instance;

        Worker() {
            Thread.Builder.OfPlatform builder = Thread.ofPlatform().name("amaru-validator-" + workerIds.incrementAndGet())
                    .daemon(true);
            if (stackSize > 0) {
                builder = builder.stackSize(stackSize);
            }
            // Assign before starting: run() reads the field.
            this.thread = builder.unstarted(this);
            this.thread.start();
        }

        String name() {
            return thread.getName();
        }

        /** @return the pending result, or null when the worker did not take the call before the deadline */
        <T> CompletableFuture<T> submit(Function<AmaruInstance, T> work, long deadlineNanos) {
            Task<T> task = new Task<>(work, new CompletableFuture<>());
            try {
                boolean taken = tasks.offer(task, Math.max(0, deadlineNanos - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
                return taken ? task.result : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task.result.completeExceptionally(e);
                return task.result;
            }
        }

        void poison() {
            poisoned = true;
            thread.interrupt();
        }

        boolean awaitTermination(Duration grace) throws InterruptedException {
            return thread.join(grace);
        }

        @Override
        public void run() {
            try {
                ensureInstance();
            } catch (Throwable t) {
                log.warn("Amaru instance creation failed on {}; retrying on its next call", name(), t);
            }
            while (!poisoned) {
                Task<?> task;
                try {
                    task = tasks.take();
                } catch (InterruptedException e) {
                    break;
                }
                run(task);
            }
            discardInstance();
        }

        private <T> void run(Task<T> task) {
            try {
                ensureInstance();
                task.result.complete(task.work.apply(instance));
            } catch (Throwable t) {
                discardInstance();
                task.result.completeExceptionally(t);
            }
        }

        private void ensureInstance() {
            if (instance == null) {
                instance = factory.get();
            }
        }

        private void discardInstance() {
            if (instance != null) {
                try {
                    instance.close();
                } catch (RuntimeException ignored) {
                    // A broken instance may fail to close; it is dropped either way.
                }
                instance = null;
            }
        }
    }

    private record Task<T>(Function<AmaruInstance, T> work, CompletableFuture<T> result) {
    }
}
