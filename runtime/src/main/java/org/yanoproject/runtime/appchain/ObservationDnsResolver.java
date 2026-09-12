package org.yanoproject.runtime.appchain;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded host-wide DNS isolation; a platform resolver must not retain an acquisition worker indefinitely. */
final class ObservationDnsResolver implements AutoCloseable {
    static final ObservationDnsResolver SHARED = new ObservationDnsResolver(4, 64, InetAddress::getAllByName);

    @FunctionalInterface
    interface Lookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Lookup lookup;
    private final ThreadPoolExecutor executor;

    ObservationDnsResolver(int workers, int capacity, Lookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        executor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = Thread.ofPlatform().daemon(true).name("observation-https-dns")
                            .inheritInheritableThreadLocals(false).unstarted(task);
                    thread.setContextClassLoader(ObservationDnsResolver.class.getClassLoader());
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    InetAddress[] resolve(String host, long deadlineNanos) throws IOException {
        if (deadlineNanos - System.nanoTime() <= 0) throw new IOException("Observation DNS deadline exceeded");
        FutureTask<InetAddress[]> task = new FutureTask<>(() -> lookup.resolve(host));
        try {
            executor.execute(task);
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new IOException("Observation DNS deadline exceeded");
            return task.get(remaining, TimeUnit.NANOSECONDS).clone();
        } catch (RejectedExecutionException full) {
            throw new IOException("Observation DNS capacity unavailable", full);
        } catch (TimeoutException timeout) {
            throw new IOException("Observation DNS deadline exceeded", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Observation DNS lookup interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new IOException("Observation DNS lookup failed", failed.getCause());
        } catch (CancellationException cancelled) {
            throw new IOException("Observation DNS lookup cancelled", cancelled);
        } finally {
            task.cancel(true);
            // Native resolution may ignore interruption. It can occupy at most
            // the fixed worker count; timed-out queued work must not accumulate.
            executor.remove(task);
        }
    }

    int queuedLookups() { return executor.getQueue().size(); }

    @Override
    public void close() {
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof FutureTask<?> task) task.cancel(true);
        }
    }
}
