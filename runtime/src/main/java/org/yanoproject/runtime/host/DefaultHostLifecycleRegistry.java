package org.yanoproject.runtime.host;

import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.plugin.PluginActivationException;
import org.yanoproject.api.plugin.operations.PluginOperationsRecorder;
import org.yanoproject.api.plugin.spi.PluginProducts;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.runtime.kernel.SubsystemHealth;
import org.yanoproject.runtime.plugins.PluginRuntimeEnvironment;
import org.yanoproject.runtime.util.LifecycleFailures;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

/**
 * Owns registration/cleanup only; scheduling and lifecycle transitions remain in NodeKernel.
 * Before assembly, construction failure may close this registry. After assembly, only the
 * kernel's final resource stage closes it; callers must never race it against kernel transitions.
 *
 * <p>Owner-id registrations are accepted only from the owner's own {@code NodePlugin.init} or assembly
 * step, and only for embedded bundles. Code reached through a nested plugin callback holds no owner
 * authority (see {@link Callers#owner()}), so it cannot impersonate the enclosing owner.</p>
 */
public final class DefaultHostLifecycleRegistry implements HostLifecycleRegistry, AutoCloseable {
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Runnable> assemblies = new LinkedHashMap<>();
    private final Map<String, BooleanSupplier> readiness = new LinkedHashMap<>();
    private final Map<String, PluginProducts> products = new LinkedHashMap<>();
    private final ThreadLocal<Boolean> assembling = new ThreadLocal<>();
    private final CloseOnce close = new CloseOnce();
    private final Callers callers;
    private final PluginOperationsRecorder recorder;
    private boolean assembled;
    private boolean sealed;
    private boolean closing;

    /** Identifies the plugin that calls the registry. */
    public interface Callers {
        /**
         * The bundle whose own {@code NodePlugin.init} or assembly step is the innermost plugin code on the
         * current thread; empty when none runs or a nested plugin callback is innermost.
         */
        Optional<String> owner();

        /** Runs an assembly step as {@code bundleId}'s own code. */
        void runAsOwner(String bundleId, Runnable step);

        /** Whether the bundle was loaded from the host class path. */
        boolean embedded(String bundleId);

        /** The callers of a host whose plugins run in {@code plugins}. */
        static Callers of(PluginRuntimeEnvironment plugins) {
            Objects.requireNonNull(plugins, "plugins");
            return new Callers() {
                @Override public Optional<String> owner() { return plugins.currentOwner(); }
                @Override public void runAsOwner(String bundleId, Runnable step) { plugins.runAsOwner(bundleId, step); }
                @Override public boolean embedded(String bundleId) { return plugins.embeddedBundle(bundleId); }
            };
        }
    }

    /**
     * @param callers caller identity of owner registrations
     * @param recorder this host's operations recorder, returned to product owners
     */
    public DefaultHostLifecycleRegistry(Callers callers, PluginOperationsRecorder recorder) {
        this.callers = Objects.requireNonNull(callers, "callers");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public void registerBeforePublication(Subsystem service) {
        Objects.requireNonNull(service, "service");
        String name = Objects.requireNonNull(service.name(), "service name");
        if (name.isBlank()) throw new IllegalArgumentException("Host service name must not be blank");
        synchronized (this) {
            if (sealed || closing || close.started()) {
                throw new IllegalStateException("Register host services during plugin initialization "
                        + "or host assembly before sealing");
            }
            if (entries.containsKey(name)) throw new IllegalArgumentException("Duplicate host service " + name);
            entries.put(name, new Entry(name, service));
        }
    }

    @Override
    public void registerAssembly(String ownerBundleId, Runnable assembly) {
        Objects.requireNonNull(assembly, "assembly");
        String owner = requireOwner(ownerBundleId, false);
        synchronized (this) {
            if (assembled || sealed || closing) {
                throw new IllegalStateException("Register assembly steps during NodePlugin.init");
            }
            if (assemblies.putIfAbsent(owner, assembly) != null) {
                throw new IllegalStateException("Duplicate assembly step for plugin '" + owner + "'");
            }
        }
    }

    @Override
    public void registerRestartReadiness(String ownerBundleId, BooleanSupplier ready) {
        Objects.requireNonNull(ready, "ready");
        String owner = requireOwner(ownerBundleId, true);
        synchronized (this) {
            requireRegistrationOpen();
            if (readiness.putIfAbsent(owner, ready) != null) {
                throw new IllegalStateException("Duplicate restart readiness for plugin '" + owner + "'");
            }
        }
    }

    @Override
    public PluginOperationsRecorder registerPluginProducts(String ownerBundleId, PluginProducts registered) {
        Objects.requireNonNull(registered, "products");
        String owner = requireOwner(ownerBundleId, true);
        synchronized (this) {
            requireRegistrationOpen();
            if (products.putIfAbsent(owner, registered) != null) {
                throw new IllegalStateException("Duplicate plugin products for plugin '" + owner + "'");
            }
        }
        return recorder;
    }

    /**
     * Runs every assembly step once, in registration order, under {@code pluginLoader} as TCCL.
     * A failure is a {@link PluginActivationException} naming its owner; the caller performs the reverse
     * construction cleanup.
     */
    public void runAssemblies(ClassLoader pluginLoader) {
        Objects.requireNonNull(pluginLoader, "pluginLoader");
        List<Map.Entry<String, Runnable>> steps;
        synchronized (this) {
            if (assembled || assembling.get() != null) {
                throw new IllegalStateException("Host assembly already ran");
            }
            assembled = true;
            steps = List.copyOf(assemblies.entrySet());
        }
        for (Map.Entry<String, Runnable> step : steps) {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            assembling.set(Boolean.TRUE);
            thread.setContextClassLoader(pluginLoader);
            try {
                callers.runAsOwner(step.getKey(), step.getValue());
            } catch (Throwable failure) {
                LifecycleFailures.rethrowIfProcessFatalReachable(failure);
                // The same startup-fatal plugin boundary as a failed NodePlugin.init: applications fail closed
                // and never render the plugin's own cause chain by default.
                throw new PluginActivationException("Plugin bundle '" + step.getKey() + "' failed host assembly",
                        step.getKey(), ContributionKind.NODE_PLUGIN.manifestKey(), step.getKey(), null, failure);
            } finally {
                thread.setContextClassLoader(previous);
                assembling.remove();
            }
        }
    }

    /** Restart preflight: every registered readiness check in registration order, stopping at the first false. */
    public boolean restartReady() {
        List<BooleanSupplier> checks;
        synchronized (this) {
            checks = List.copyOf(readiness.values());
        }
        for (BooleanSupplier check : checks) {
            if (!check.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    /** Resumes registered plugin products in registration order. */
    public void resumePluginProducts() {
        for (PluginProducts registered : registeredProducts()) {
            registered.resume();
        }
    }

    /** Seals registered plugin products in reverse registration order, attempting every owner. */
    public void sealPluginProducts() {
        Throwable failure = null;
        for (PluginProducts registered : registeredProducts().reversed()) {
            try { registered.sealAndAwait(); }
            catch (Throwable next) { failure = LifecycleFailures.merge(failure, next); }
        }
        rethrow(failure);
    }

    private synchronized List<PluginProducts> registeredProducts() {
        return new ArrayList<>(products.values());
    }

    private String requireOwner(String ownerBundleId, boolean assemblyAllowed) {
        Objects.requireNonNull(ownerBundleId, "ownerBundleId");
        if (assembling.get() != null && !assemblyAllowed) {
            throw new IllegalStateException("Assembly steps cannot register further assembly steps");
        }
        String caller = callers.owner().orElseThrow(() -> new IllegalStateException(
                "Owner registrations are accepted only from the owner's own NodePlugin.init or assembly step, "
                        + "not from a nested plugin callback"));
        if (!caller.equals(ownerBundleId)) {
            throw new IllegalStateException("Plugin '" + caller + "' cannot register for owner '"
                    + ownerBundleId + "'");
        }
        if (!callers.embedded(ownerBundleId)) {
            throw new IllegalStateException("Only embedded host-class-path bundles may register as owners; '"
                    + ownerBundleId + "' is not embedded");
        }
        return ownerBundleId;
    }

    private void requireRegistrationOpen() {
        if (sealed || closing || close.started()) {
            throw new IllegalStateException("Register during plugin initialization or host assembly before sealing");
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
