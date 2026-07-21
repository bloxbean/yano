package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventFilter;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.api.plugin.UtxoFilterContext;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Non-owning platform facades for callbacks registered through {@code PluginContext}. */
final class PluginContextFacades {
    static final long CURRENT_GENERATION = -1;

    private PluginContextFacades() {
    }

    enum ResourceScope {
        START_CYCLE
    }

    static EventBus eventBus(
            EventBus delegate,
            ClassLoader callbackLoader,
            ClassLoader platformLoader,
            ManagedCallbackResources callbacks,
            Supplier<ResourceScope> scopeSupplier) {
        return new ContextEventBus(
                Objects.requireNonNull(delegate, "delegate"),
                PluginThreadContext.effective(callbackLoader),
                PluginThreadContext.effective(platformLoader),
                Objects.requireNonNull(callbacks, "callbacks"),
                Objects.requireNonNull(scopeSupplier, "scopeSupplier"));
    }

    static ScheduledExecutorService scheduler(
            ScheduledExecutorService delegate,
            ClassLoader callbackLoader,
            ClassLoader platformLoader,
            ManagedCallbackResources callbacks,
            Supplier<ResourceScope> scopeSupplier) {
        return new ContextScheduledExecutorService(
                Objects.requireNonNull(delegate, "delegate"),
                PluginThreadContext.effective(callbackLoader),
                PluginThreadContext.effective(platformLoader),
                Objects.requireNonNull(callbacks, "callbacks"),
                Objects.requireNonNull(scopeSupplier, "scopeSupplier"));
    }

    static StorageFilter storageFilter(
            StorageFilter delegate,
            ClassLoader loader,
            ManagedCallbackResources callbacks) {
        return new ContextStorageFilter(
                Objects.requireNonNull(delegate, "delegate"),
                PluginThreadContext.effective(loader),
                Objects.requireNonNull(callbacks, "callbacks"));
    }

    static StorageFilter storageFilterDelegate(StorageFilter filter) {
        return filter instanceof ContextStorageFilter contextual
                ? contextual.delegate() : filter;
    }

    /**
     * Per-manager callback admission and managed-resource owner. A generation
     * future is registered before its callbacks are admitted. Sealing is
     * bounded: it cancels cycle resources synchronously, while the future
     * completes only after every callback admitted before the seal returns.
     */
    static final class ManagedCallbackResources {
        private final ThreadLocal<Integer> localDepth =
                ThreadLocal.withInitial(() -> 0);
        private final Consumer<CompletableFuture<Void>> completionRegistrar;
        private final Map<ManagedResource, ResourceScope> resources =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final List<Throwable> cleanupFailures = new ArrayList<>();
        private CallbackState state = CallbackState.OPEN;
        private CompletableFuture<Void> completion = new CompletableFuture<>();
        private long generation;
        private int active;
        private boolean cleanupInProgress;

        ManagedCallbackResources(
                Consumer<CompletableFuture<Void>> completionRegistrar
        ) {
            this.completionRegistrar = Objects.requireNonNull(
                    completionRegistrar, "completionRegistrar");
            completionRegistrar.accept(completion);
        }

        synchronized long generation() {
            if (state != CallbackState.OPEN) {
                throw new IllegalStateException("Managed plugin callback admission is sealed");
            }
            return generation;
        }

        <T, X extends Throwable> T callOrElse(
                long expectedGeneration,
                PluginThreadContext.ThrowingSupplier<T, X> callback,
                T skippedValue
        ) throws X {
            return call(expectedGeneration, callback, skippedValue, true);
        }

        <T, X extends Throwable> T callRequired(
                long expectedGeneration,
                PluginThreadContext.ThrowingSupplier<T, X> callback
        ) throws X {
            return call(expectedGeneration, callback, null, false);
        }

        private <T, X extends Throwable> T call(
                long expectedGeneration,
                PluginThreadContext.ThrowingSupplier<T, X> callback,
                T skippedValue,
                boolean skipWhenSealed
        ) throws X {
            Objects.requireNonNull(callback, "callback");
            int depth = localDepth.get();
            boolean root = depth == 0;
            synchronized (this) {
                // Nested callbacks inherit their root callback's admission so
                // a shutdown seal cannot split one synchronous dispatch. They
                // must not, however, inherit a different generation: a current
                // publish can synchronously encounter an old listener wrapper
                // retained by a platform queue after restart.
                boolean wrongGeneration = expectedGeneration != CURRENT_GENERATION
                        && expectedGeneration != generation;
                if (wrongGeneration || root && state != CallbackState.OPEN) {
                    if (root) {
                        localDepth.remove();
                    }
                    if (skipWhenSealed) {
                        return skippedValue;
                    }
                    throw new CallbackAdmissionException(
                            "Managed plugin callback admission is sealed");
                }
                if (root) {
                    active++;
                }
            }
            localDepth.set(depth + 1);
            try {
                return callback.get();
            } finally {
                int remainingDepth = localDepth.get() - 1;
                if (remainingDepth == 0) {
                    localDepth.remove();
                    synchronized (this) {
                        active--;
                        completeIfQuiescent();
                    }
                } else {
                    localDepth.set(remainingDepth);
                }
            }
        }

        <X extends Throwable> void runOrSkip(
                long expectedGeneration,
                PluginThreadContext.ThrowingRunnable<X> callback
        ) throws X {
            callOrElse(expectedGeneration, () -> {
                callback.run();
                return null;
            }, null);
        }

        <X extends Throwable> void runRequired(
                long expectedGeneration,
                PluginThreadContext.ThrowingRunnable<X> callback
        ) throws X {
            callRequired(expectedGeneration, () -> {
                callback.run();
                return null;
            });
        }

        void requireNotInCallback(String action) {
            if (localDepth.get() != 0) {
                throw new IllegalStateException(
                        "Cannot " + action + " from a managed plugin callback");
            }
        }

        synchronized void register(ManagedResource resource, ResourceScope scope) {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(scope, "scope");
            if (state != CallbackState.OPEN) {
                throw new IllegalStateException("Managed plugin callback admission is sealed");
            }
            resources.put(resource, scope);
        }

        void unregister(ManagedResource resource) {
            resources.remove(resource);
        }

        CompletableFuture<Void> sealStartCycle() {
            return seal(false);
        }

        CompletableFuture<Void> sealTerminal() {
            return seal(true);
        }

        private CompletableFuture<Void> seal(boolean terminal) {
            List<ManagedResource> toClose;
            CompletableFuture<Void> signal;
            synchronized (this) {
                if (state == CallbackState.TERMINAL) {
                    return completion;
                }
                if (state == CallbackState.SEALED && !terminal) {
                    return completion;
                }
                state = terminal ? CallbackState.TERMINAL : CallbackState.SEALED;
                cleanupInProgress = true;
                signal = completion;
                synchronized (resources) {
                    toClose = resources.entrySet().stream()
                            .map(Map.Entry::getKey)
                            .toList();
                }
            }

            for (ManagedResource resource : toClose) {
                try {
                    resource.closeManaged();
                } catch (Throwable failure) {
                    synchronized (this) {
                        cleanupFailures.add(failure);
                    }
                }
            }

            synchronized (this) {
                cleanupInProgress = false;
                completeIfQuiescent();
                return signal;
            }
        }

        synchronized void resumeNewGeneration() {
            if (state == CallbackState.TERMINAL) {
                throw new IllegalStateException("Managed plugin callbacks are terminally closed");
            }
            if (state != CallbackState.SEALED || active != 0 || !completion.isDone()) {
                throw new IllegalStateException(
                        "Managed plugin callbacks are not quiescent for restart");
            }
            CompletableFuture<Void> next = new CompletableFuture<>();
            // Registration happens before OPEN is published, so no callback in
            // the new generation can outrun its provider/loader lifetime fence.
            completionRegistrar.accept(next);
            completion = next;
            generation++;
            state = CallbackState.OPEN;
        }

        synchronized CompletableFuture<Void> completion() {
            return completion;
        }

        void awaitCompletion(CompletableFuture<Void> signal) {
            Objects.requireNonNull(signal, "signal");
            requireNotInCallback("await managed plugin callbacks");
            signal.handle((ignored, failure) -> null).join();
        }

        synchronized List<Throwable> drainCleanupFailures() {
            List<Throwable> snapshot = List.copyOf(cleanupFailures);
            cleanupFailures.clear();
            return snapshot;
        }

        private void completeIfQuiescent() {
            if (state != CallbackState.OPEN && active == 0 && !cleanupInProgress) {
                completion.complete(null);
            }
        }

        private enum CallbackState {
            OPEN,
            SEALED,
            TERMINAL
        }
    }

    private static final class CallbackAdmissionException extends IllegalStateException {
        private CallbackAdmissionException(String message) {
            super(message);
        }
    }

    private interface ManagedResource {
        void closeManaged() throws Exception;
    }

    private static final class ContextEventBus implements EventBus {
        private final EventBus delegate;
        private final ClassLoader callbackLoader;
        private final ClassLoader platformLoader;
        private final ManagedCallbackResources callbacks;
        private final Supplier<ResourceScope> scopeSupplier;

        private ContextEventBus(
                EventBus delegate,
                ClassLoader callbackLoader,
                ClassLoader platformLoader,
                ManagedCallbackResources callbacks,
                Supplier<ResourceScope> scopeSupplier
        ) {
            this.delegate = delegate;
            this.callbackLoader = callbackLoader;
            this.platformLoader = platformLoader;
            this.callbacks = callbacks;
            this.scopeSupplier = scopeSupplier;
        }

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> type,
                EventListener<E> listener,
                SubscriptionOptions options) {
            Objects.requireNonNull(listener, "listener");
            // Registration itself is an admitted callback. Otherwise a stop can
            // observe a registered resource whose delegate handoff is still in
            // progress and declare the plugin/class-loader lifetime quiescent
            // before subscribe() returns to plugin code.
            return callbacks.callRequired(CURRENT_GENERATION, () -> {
                ResourceScope scope = scopeSupplier.get();
                long expectedGeneration = callbacks.generation();
                ManagedSubscription<E> subscription = new ManagedSubscription<>(
                        delegate, type, listener, options, callbackLoader, platformLoader,
                        callbacks, expectedGeneration);
                callbacks.register(subscription, scope);
                try {
                    subscription.open();
                    return subscription;
                } catch (Throwable failure) {
                    callbacks.unregister(subscription);
                    Throwable outcome = failure;
                    try {
                        subscription.closeManaged();
                    } catch (Throwable cleanupFailure) {
                        outcome = LifecycleFailures.merge(outcome, cleanupFailure);
                    }
                    if (outcome instanceof Error error) throw error;
                    if (outcome instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("Plugin event subscription failed", outcome);
                }
            });
        }

        @Override
        public <E extends Event> void publish(
                E event,
                EventMetadata metadata,
                PublishOptions options) {
            callbacks.runRequired(CURRENT_GENERATION,
                    () -> PluginThreadContext.run(platformLoader,
                            () -> delegate.publish(event, metadata, options)));
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException(
                    "Plugins cannot close the shared EventBus");
        }
    }

    private static final class ManagedSubscription<E extends Event>
            implements SubscriptionHandle, ManagedResource {
        private final EventBus eventBus;
        private final Class<E> type;
        private final EventListener<E> listener;
        private final SubscriptionOptions options;
        private final ClassLoader callbackLoader;
        private final ClassLoader platformLoader;
        private final ManagedCallbackResources callbacks;
        private final long expectedGeneration;
        private SubscriptionHandle delegate;
        private boolean closed;

        private ManagedSubscription(
                EventBus eventBus,
                Class<E> type,
                EventListener<E> listener,
                SubscriptionOptions options,
                ClassLoader callbackLoader,
                ClassLoader platformLoader,
                ManagedCallbackResources callbacks,
                long expectedGeneration
        ) {
            this.eventBus = eventBus;
            this.type = type;
            this.listener = listener;
            this.options = options;
            this.callbackLoader = callbackLoader;
            this.platformLoader = platformLoader;
            this.callbacks = callbacks;
            this.expectedGeneration = expectedGeneration;
        }

        private void open() {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Plugin event subscription is closed");
                }
            }
            EventListener<E> contextualListener = context -> callbacks.runOrSkip(
                    expectedGeneration,
                    () -> PluginThreadContext.run(callbackLoader,
                            () -> listener.onEvent(context)));
            SubscriptionOptions contextualOptions = contextualOptions(options);
            SubscriptionHandle created = Objects.requireNonNull(PluginThreadContext.call(
                        platformLoader,
                        () -> eventBus.subscribe(type, contextualListener,
                                contextualOptions)),
                "EventBus returned a null subscription handle");
            boolean closeCreated;
            synchronized (this) {
                closeCreated = closed;
                if (!closeCreated) {
                    delegate = created;
                }
            }
            if (closeCreated) {
                PluginThreadContext.run(platformLoader, created::close);
                throw new IllegalStateException(
                        "Plugin event subscription was sealed during registration");
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
            }
            try {
                callbacks.runRequired(expectedGeneration, this::closeManaged);
            } catch (CallbackAdmissionException sealed) {
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                }
                throw sealed;
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Plugin event subscription close failed", failure);
            }
        }

        @Override
        public void closeManaged() throws Exception {
            SubscriptionHandle current;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                current = delegate;
                delegate = null;
            }
            callbacks.unregister(this);
            if (current != null) {
                PluginThreadContext.run(platformLoader, current::close);
            }
        }

        @Override
        public boolean isActive() {
            SubscriptionHandle current;
            synchronized (this) {
                if (closed || delegate == null) {
                    return false;
                }
                current = delegate;
            }
            return callbacks.callOrElse(expectedGeneration,
                    () -> PluginThreadContext.call(platformLoader, current::isActive),
                    false);
        }

        private SubscriptionOptions contextualOptions(SubscriptionOptions source) {
            if (source == null || source.filter() == null) {
                return source;
            }
            EventFilter<E> filter = source.filter();
            SubscriptionOptions.Builder builder = SubscriptionOptions.builder()
                    .bufferSize(source.bufferSize())
                    .overflow(source.overflow())
                    .priority(source.priority())
                    .filter((E event, EventMetadata metadata) -> callbacks.callOrElse(
                            expectedGeneration,
                            () -> PluginThreadContext.call(callbackLoader,
                                    () -> filter.test(event, metadata)),
                            false));
            if (source.executor() != null) {
                builder.executor(source.executor());
            }
            return builder.build();
        }
    }

    private static final class ContextScheduledExecutorService
            extends AbstractExecutorService implements ScheduledExecutorService {
        private final ScheduledExecutorService delegate;
        private final ClassLoader callbackLoader;
        private final ClassLoader platformLoader;
        private final ManagedCallbackResources callbacks;
        private final Supplier<ResourceScope> scopeSupplier;

        private ContextScheduledExecutorService(
                ScheduledExecutorService delegate,
                ClassLoader callbackLoader,
                ClassLoader platformLoader,
                ManagedCallbackResources callbacks,
                Supplier<ResourceScope> scopeSupplier
        ) {
            this.delegate = delegate;
            this.callbackLoader = callbackLoader;
            this.platformLoader = platformLoader;
            this.callbacks = callbacks;
            this.scopeSupplier = scopeSupplier;
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            callbacks.runRequired(CURRENT_GENERATION, () -> {
                TaskRegistration task = task();
                FutureTask<Void> future = new FutureTask<>(() -> {
                    try {
                        task.run(command);
                    } finally {
                        task.finish();
                    }
                }, null);
                // AbstractExecutorService.submit()/invokeAll() pass their own
                // FutureTask to execute(). Keep that exposed task linked to the
                // delegate wrapper so a generation stop cannot leave the
                // Future returned to plugin code pending forever.
                task.setFuture(future,
                        command instanceof ContextFutureTask<?> submitted ? submitted : null);
                try {
                    PluginThreadContext.run(platformLoader, () -> delegate.execute(future));
                } catch (Throwable failure) {
                    task.finish();
                    throw failure;
                }
            });
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command,
                long delay,
                TimeUnit unit) {
            Objects.requireNonNull(command, "command");
            return callbacks.callRequired(CURRENT_GENERATION, () -> {
                TaskRegistration task = task();
                try {
                    ScheduledFuture<?> future = PluginThreadContext.call(platformLoader,
                            () -> delegate.schedule(() -> {
                                try {
                                    task.run(command);
                                } finally {
                                    task.finish();
                                }
                            }, delay, unit));
                    task.setFuture(future);
                    return new ManagedScheduledFuture<>(future, task);
                } catch (Throwable failure) {
                    task.finish();
                    throw failure;
                }
            });
        }

        @Override
        public <V> ScheduledFuture<V> schedule(
                Callable<V> callable,
                long delay,
                TimeUnit unit) {
            Objects.requireNonNull(callable, "callable");
            return callbacks.callRequired(CURRENT_GENERATION, () -> {
                TaskRegistration task = task();
                try {
                    ScheduledFuture<V> future = PluginThreadContext.call(platformLoader,
                            () -> delegate.schedule(() -> {
                                try {
                                    return task.call(callable);
                                } finally {
                                    task.finish();
                                }
                            }, delay, unit));
                    task.setFuture(future);
                    return new ManagedScheduledFuture<>(future, task);
                } catch (Throwable failure) {
                    task.finish();
                    throw failure;
                }
            });
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command,
                long initialDelay,
                long period,
                TimeUnit unit) {
            return scheduleRecurring(command,
                    () -> delegate.scheduleAtFixedRate(
                            recurring(command), initialDelay, period, unit));
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command,
                long initialDelay,
                long delay,
                TimeUnit unit) {
            return scheduleRecurring(command,
                    () -> delegate.scheduleWithFixedDelay(
                            recurring(command), initialDelay, delay, unit));
        }

        private ScheduledFuture<?> scheduleRecurring(
                Runnable command,
                Supplier<ScheduledFuture<?>> scheduler) {
            Objects.requireNonNull(command, "command");
            return callbacks.callRequired(CURRENT_GENERATION, () -> {
                TaskRegistration task = task();
                recurringTask.set(task);
                try {
                    ScheduledFuture<?> future = PluginThreadContext.call(
                            platformLoader, scheduler::get);
                    task.setFuture(future);
                    return new ManagedScheduledFuture<>(future, task);
                } catch (Throwable failure) {
                    task.finish();
                    throw failure;
                } finally {
                    recurringTask.remove();
                }
            });
        }

        private final ThreadLocal<TaskRegistration> recurringTask = new ThreadLocal<>();

        private Runnable recurring(Runnable command) {
            TaskRegistration task = recurringTask.get();
            return () -> {
                try {
                    task.run(command);
                } catch (Throwable failure) {
                    task.finish();
                    throw failure;
                }
            };
        }

        private TaskRegistration task() {
            ResourceScope lifetimeScope = scopeSupplier.get();
            long expectedGeneration = callbacks.generation();
            TaskRegistration task = new TaskRegistration(
                    callbacks, expectedGeneration,
                    callbackLoader, platformLoader);
            callbacks.register(task, lifetimeScope);
            return task;
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException(
                    "Plugins cannot shut down the shared scheduler");
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException(
                    "Plugins cannot shut down the shared scheduler");
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException(
                    "Plugins cannot close the shared scheduler");
        }

        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            return new ContextFutureTask<>(callable);
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
            return new ContextFutureTask<>(runnable, value);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    /** Marker for futures created by this facade's inherited submit/invoke APIs. */
    private static final class ContextFutureTask<V> extends FutureTask<V> {
        private ContextFutureTask(Callable<V> callable) {
            super(callable);
        }

        private ContextFutureTask(Runnable runnable, V value) {
            super(runnable, value);
        }
    }

    private static final class TaskRegistration implements ManagedResource {
        private final ManagedCallbackResources callbacks;
        private final long expectedGeneration;
        private final ClassLoader callbackLoader;
        private final ClassLoader platformLoader;
        private Future<?> future;
        private Future<?> exposedFuture;
        private boolean closed;

        private TaskRegistration(
                ManagedCallbackResources callbacks,
                long expectedGeneration,
                ClassLoader callbackLoader,
                ClassLoader platformLoader
        ) {
            this.callbacks = callbacks;
            this.expectedGeneration = expectedGeneration;
            this.callbackLoader = callbackLoader;
            this.platformLoader = platformLoader;
        }

        private void run(Runnable command) {
            callbacks.runOrSkip(expectedGeneration,
                    () -> PluginThreadContext.run(callbackLoader, command::run));
        }

        private <V> V call(Callable<V> callable) throws Exception {
            return callbacks.callOrElse(expectedGeneration,
                    () -> PluginThreadContext.call(callbackLoader, callable::call), null);
        }

        private synchronized void setFuture(Future<?> value) {
            setFuture(value, null);
        }

        private synchronized void setFuture(Future<?> value, Future<?> exposed) {
            future = Objects.requireNonNull(value, "future");
            exposedFuture = exposed;
            if (closed) {
                cancelFutures(future, exposedFuture, true);
            }
        }

        private void finish() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            callbacks.unregister(this);
        }

        private boolean cancelFromPlugin(boolean mayInterruptIfRunning) {
            synchronized (this) {
                if (closed) {
                    return isAnyCancelled();
                }
            }
            try {
                return callbacks.callRequired(expectedGeneration,
                        () -> cancelManaged(mayInterruptIfRunning));
            } catch (CallbackAdmissionException sealed) {
                synchronized (this) {
                    if (closed) {
                        return isAnyCancelled();
                    }
                }
                throw sealed;
            }
        }

        private boolean cancelManaged(boolean mayInterruptIfRunning) {
            Future<?> current;
            Future<?> exposed;
            synchronized (this) {
                if (closed) {
                    return isAnyCancelled();
                }
                closed = true;
                current = future;
                exposed = exposedFuture;
            }
            callbacks.unregister(this);
            return cancelFutures(current, exposed, mayInterruptIfRunning);
        }

        private boolean isAnyCancelled() {
            return future != null && future.isCancelled()
                    || exposedFuture != null && exposedFuture.isCancelled();
        }

        private boolean cancelFutures(
                Future<?> current,
                Future<?> exposed,
                boolean mayInterruptIfRunning
        ) {
            boolean cancelled = false;
            Throwable failure = null;
            if (current != null) {
                try {
                    cancelled = PluginThreadContext.call(platformLoader,
                            () -> current.cancel(mayInterruptIfRunning));
                } catch (Throwable next) {
                    failure = next;
                }
            }
            if (exposed != null && exposed != current) {
                try {
                    boolean exposedCancelled = PluginThreadContext.call(platformLoader,
                            () -> exposed.cancel(mayInterruptIfRunning));
                    cancelled = exposedCancelled || cancelled;
                } catch (Throwable next) {
                    failure = LifecycleFailures.merge(failure, next);
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            return cancelled;
        }

        @Override
        public void closeManaged() {
            cancelManaged(true);
        }
    }

    private record ManagedScheduledFuture<V>(
            ScheduledFuture<V> delegate,
            TaskRegistration registration
    ) implements ScheduledFuture<V> {
        @Override public long getDelay(TimeUnit unit) { return delegate.getDelay(unit); }
        @Override public int compareTo(Delayed other) { return delegate.compareTo(other); }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return registration.cancelFromPlugin(mayInterruptIfRunning);
        }
        @Override public boolean isCancelled() { return delegate.isCancelled(); }
        @Override public boolean isDone() { return delegate.isDone(); }
        @Override public V get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }
        @Override public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }
    }

    private record ContextStorageFilter(
            StorageFilter delegate,
            ClassLoader loader,
            ManagedCallbackResources callbacks
    ) implements StorageFilter {

        @Override
        public boolean acceptUtxoOutput(
                UtxoFilterContext ctx,
                Block block,
                TransactionBody txBody) {
            return callbacks.callOrElse(CURRENT_GENERATION,
                    () -> PluginThreadContext.call(loader,
                            () -> delegate.acceptUtxoOutput(ctx, block, txBody)),
                    false);
        }

        @Override
        public int priority() {
            return callbacks.callOrElse(CURRENT_GENERATION,
                    () -> PluginThreadContext.call(loader, delegate::priority),
                    Integer.MAX_VALUE);
        }
    }
}
