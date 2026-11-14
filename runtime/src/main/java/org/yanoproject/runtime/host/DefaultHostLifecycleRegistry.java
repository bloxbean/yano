package org.yanoproject.runtime.host;

import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.runtime.kernel.SubsystemHealth;
import org.yanoproject.runtime.util.LifecycleFailures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Owns registration/cleanup only; scheduling and lifecycle transitions remain in NodeKernel.
 * Before assembly, construction failure may close this registry. After assembly, only the
 * kernel's final resource stage closes it; callers must never race it against kernel transitions.
 */
public final class DefaultHostLifecycleRegistry implements HostLifecycleRegistry, AutoCloseable {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final CloseOnce close = new CloseOnce();
    private boolean sealed;
    private boolean closing;

    @Override
    public void registerBeforePublication(Subsystem service) {
        Objects.requireNonNull(service, "service");
        String name = Objects.requireNonNull(service.name(), "service name");
        if (name.isBlank()) throw new IllegalArgumentException("Host service name must not be blank");
        synchronized (this) {
            if (sealed || closing || close.started()) {
                throw new IllegalStateException("Register host services during plugin initialization");
            }
            if (entries.containsKey(name)) throw new IllegalArgumentException("Duplicate host service " + name);
            entries.put(name, new Entry(name, service));
        }
    }

    public synchronized void seal() { sealed = true; }

    public synchronized List<Subsystem> subsystems() {
        if (!sealed) throw new IllegalStateException("Seal host service registration before kernel assembly");
        return List.copyOf(entries.values());
    }

    @Override
    public void close() {
        close.run(() -> {
            List<Entry> owned;
            synchronized (this) {
                closing = true;
                sealed = true;
                owned = List.copyOf(entries.values());
            }
            Throwable failure = null;
            for (Entry entry : owned.reversed()) {
                try { entry.close(); }
                catch (Throwable next) { failure = LifecycleFailures.merge(failure, next); }
            }
            rethrow(failure);
        });
    }

    private static final class Entry implements Subsystem {
        private final String name;
        private final Subsystem delegate;
        private final CloseOnce close = new CloseOnce();

        private Entry(String name, Subsystem delegate) { this.name = name; this.delegate = delegate; }
        @Override public String name() { return name; }
        @Override public void init(SubsystemContext context) { delegate.init(context); }
        @Override public SubsystemHealth health() { return delegate.health(); }
        @Override public void start() {
            if (close.started()) throw new IllegalStateException("Host service is closed: " + name);
            try { delegate.start(); }
            catch (Throwable failure) {
                try { delegate.stop(); }
                catch (Throwable cleanup) { failure = LifecycleFailures.merge(failure, cleanup); }
                try { close(); }
                catch (Throwable cleanup) { failure = LifecycleFailures.merge(failure, cleanup); }
                rethrow(failure);
            }
        }
        @Override public void stop() { if (!close.started()) delegate.stop(); }
        @Override public void close() { close.run(delegate::close); }
    }

    /** Concurrent callers await cleanup; reentrant calls from the owner cannot wait on themselves. */
    private static final class CloseOnce {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Thread owner;

        synchronized boolean started() { return owner != null; }

        void run(Runnable cleanup) {
            boolean first;
            synchronized (this) {
                first = owner == null;
                if (first) owner = Thread.currentThread();
                else if (owner == Thread.currentThread() && !completion.isDone()) return;
            }
            if (first) {
                try { cleanup.run(); completion.complete(null); }
                catch (Throwable failure) { completion.completeExceptionally(failure); rethrow(failure); }
            } else {
                try { completion.join(); }
                catch (CompletionException failure) { rethrow(failure.getCause()); }
            }
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("Host service cleanup failed", failure);
    }
}
