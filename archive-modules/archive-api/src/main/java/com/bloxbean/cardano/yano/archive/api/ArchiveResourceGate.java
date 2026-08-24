package com.bloxbean.cardano.yano.archive.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A fair, named resource gate with wait diagnostics.
 *
 * <p>This deliberately keeps the existing fair {@link Semaphore} as the single
 * FIFO mechanism; it adds only the ability to tell ordinary queueing apart from
 * a stuck operation, and to report who currently holds the resource. There is no
 * additional scheduler, queue, or priority scheme.
 *
 * <p>Acquisition is interruptible. Interruption is surfaced as a failed
 * acquisition with the interrupt flag restored, never as a successful one.
 */
public final class ArchiveResourceGate {
    /** Receives a structured diagnostic each time a caller is still waiting at the warn interval. */
    @FunctionalInterface
    public interface WaitObserver {
        void stillWaiting(String gate, String operation, Duration waited, String holderDetail);
    }

    private final String name;
    private final int totalPermits;
    private final Semaphore permits;
    private final ArchiveWaitPolicy defaultPolicy;
    private final WaitObserver observer;
    /**
     * Lazily created so class initialization spawns no thread: this module is
     * built into a GraalVM native image, where an executor captured during
     * build-time initialization cannot be written to the image heap. The thread
     * is a daemon and is shared by every gate, so it needs no shutdown hook.
     */
    private static final class Watchdog {
        static final ScheduledExecutorService INSTANCE =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "yano-archive-wait-watchdog");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private final Map<Long, Holder> holders = new ConcurrentHashMap<>();
    private final Map<Long, Holder> waiters = new ConcurrentHashMap<>();
    private final AtomicLong nextHolderId = new AtomicLong();
    private final AtomicReference<ArchiveResourceDiagnostics.WaitEvent> lastWarning = new AtomicReference<>();

    public ArchiveResourceGate(String name, int totalPermits, ArchiveWaitPolicy defaultPolicy,
                               WaitObserver observer) {
        this(name, totalPermits, new Semaphore(totalPermits, true), defaultPolicy, observer);
    }

    /**
     * Wraps an existing fair semaphore. The semaphore remains the single FIFO
     * mechanism; this only adds wait diagnostics around it.
     */
    public ArchiveResourceGate(String name, int totalPermits, Semaphore permits,
                               ArchiveWaitPolicy defaultPolicy, WaitObserver observer) {
        this.name = Objects.requireNonNull(name, "name");
        if (totalPermits < 1) throw new IllegalArgumentException("totalPermits must be positive");
        this.totalPermits = totalPermits;
        this.permits = Objects.requireNonNull(permits, "permits");
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        this.observer = observer == null ? (gate, operation, waited, holder) -> { } : observer;
    }

    /**
     * Waits for a permit, warning at the policy interval and failing only at the
     * stuck threshold.
     *
     * @return holder id to pass to {@link #release(long)}
     * @throws ArchiveStuckOperationException when the stuck threshold elapses
     */
    public long acquire(String operation) {
        return acquire(operation, defaultPolicy);
    }

    public long acquire(String operation, ArchiveWaitPolicy policy) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(policy, "policy");
        long startNanos = System.nanoTime();
        try {
            // Fast path for an uncontended gate. The timed form is used rather
            // than the untimed one because the untimed tryAcquire barges ahead of
            // queued waiters even on a fair semaphore, which would undo the FIFO
            // guarantee. It also means no watchdog thread exists until a caller
            // genuinely waits.
            if (permits.tryAcquire(0, TimeUnit.NANOSECONDS)) return register(operation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchiveStoreException("interrupted waiting for archive " + name
                    + " while running " + operation, e);
        }
        long waiterId = nextHolderId.incrementAndGet();
        waiters.put(waiterId, new Holder(operation, Instant.now()));
        ScheduledFuture<?> watch = startWatchdog(waiterId, policy);
        try {
            // A single timed acquire. Slicing this into warn-interval attempts
            // would cancel and re-enqueue the waiter each interval, demoting the
            // longest waiter to the tail of the fair queue and letting newer
            // callers overtake it -- the opposite of the FIFO guarantee this gate
            // exists to preserve. Warnings come from the watchdog instead.
            if (permits.tryAcquire(policy.stuckThreshold().toNanos(), TimeUnit.NANOSECONDS)) {
                return register(operation);
            }
            Duration waited = Duration.ofNanos(System.nanoTime() - startNanos);
            throw new ArchiveStuckOperationException(name, operation, waited, holderDetail());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchiveStoreException("interrupted waiting for archive " + name
                    + " while running " + operation, e);
        } finally {
            if (watch != null) watch.cancel(false);
            waiters.remove(waiterId);
        }
    }

    /**
     * Emits the still-waiting diagnostic without touching the waiter's queue
     * position. One shared daemon thread serves every gate and only runs while a
     * caller is actually waiting.
     */
    private ScheduledFuture<?> startWatchdog(long waiterId, ArchiveWaitPolicy policy) {
        long interval = policy.warnInterval().toNanos();
        if (interval >= policy.stuckThreshold().toNanos()) return null;
        return Watchdog.INSTANCE.scheduleAtFixedRate(() -> {
            Holder waiting = waiters.get(waiterId);
            if (waiting == null) return;
            Duration waited = Duration.between(waiting.since(), Instant.now());
            String detail = holderDetail();
            lastWarning.set(new ArchiveResourceDiagnostics.WaitEvent(
                    name, waiting.operation(), waited, detail, Instant.now()));
            try {
                observer.stillWaiting(name, waiting.operation(), waited, detail);
            } catch (RuntimeException ignored) {
                // Diagnostics must never disturb the waiting caller.
            }
        }, interval, interval, TimeUnit.NANOSECONDS);
    }

    /** Non-blocking acquisition used by deferred close paths. */
    public Optional<Long> tryAcquire(String operation) {
        Objects.requireNonNull(operation, "operation");
        return permits.tryAcquire() ? Optional.of(register(operation)) : Optional.empty();
    }

    /**
     * Releases exactly the permit identified by {@code holderId}, exactly once.
     * An unknown or already-released id is a no-op, so a failed acquisition can
     * never release a permit another caller holds.
     */
    public void release(long holderId) {
        if (holders.remove(holderId) != null) permits.release();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int totalPermits() {
        return totalPermits;
    }

    public String name() {
        return name;
    }

    public Optional<ArchiveResourceDiagnostics.WaitEvent> lastWaitWarning() {
        return Optional.ofNullable(lastWarning.get());
    }

    public ArchiveResourceDiagnostics.GateUsage usage() {
        List<Holder> active = new ArrayList<>(holders.values());
        Holder oldest = active.stream().min((left, right) -> left.since.compareTo(right.since)).orElse(null);
        Instant now = Instant.now();
        return new ArchiveResourceDiagnostics.GateUsage(name, totalPermits - permits.availablePermits(),
                totalPermits, waiters.size(),
                oldest == null ? "" : oldest.operation,
                oldest == null ? Duration.ZERO : Duration.between(oldest.since, now));
    }

    private long register(String operation) {
        long id = nextHolderId.incrementAndGet();
        holders.put(id, new Holder(operation, Instant.now()));
        return id;
    }

    private String holderDetail() {
        ArchiveResourceDiagnostics.GateUsage usage = usage();
        if (usage.holder().isEmpty()) {
            return "no recorded holder; " + usage.inUse() + "/" + usage.totalPermits() + " permits in use";
        }
        return "held by " + usage.holder() + " for " + usage.holderDuration().toSeconds() + "s ("
                + usage.inUse() + "/" + usage.totalPermits() + " permits in use, "
                + usage.waiters() + " waiting)";
    }

    private record Holder(String operation, Instant since) { }
}
