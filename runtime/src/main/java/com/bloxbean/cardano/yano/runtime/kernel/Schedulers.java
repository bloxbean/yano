package com.bloxbean.cardano.yano.runtime.kernel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Kernel-owned executors shared by subsystems.
 */
public final class Schedulers implements AutoCloseable {
    private final ScheduledExecutorService scheduledExecutor;
    private final ExecutorService taskExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public Schedulers() {
        this(Executors.newScheduledThreadPool(2, Thread.ofVirtual().name("YanoKernelScheduler-", 0).factory()),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("YanoKernelTask-", 0).factory()));
    }

    public Schedulers(ScheduledExecutorService scheduledExecutor, ExecutorService taskExecutor) {
        this.scheduledExecutor = Objects.requireNonNull(scheduledExecutor, "scheduledExecutor");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    }

    public ScheduledExecutorService scheduled() {
        return scheduledExecutor;
    }

    public ExecutorService tasks() {
        return taskExecutor;
    }

    public Thread startVirtualThread(String name, Runnable task) {
        Objects.requireNonNull(task, "task");
        return Thread.ofVirtual()
                .name(name != null && !name.isBlank() ? name : "YanoKernelTask")
                .start(task);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        shutdown(taskExecutor);
        shutdown(scheduledExecutor);
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
