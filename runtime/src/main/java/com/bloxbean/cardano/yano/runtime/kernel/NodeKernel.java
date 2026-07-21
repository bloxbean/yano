package com.bloxbean.cardano.yano.runtime.kernel;

import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Small lifecycle owner for an ordered subsystem assembly.
 *
 * <p>Lifecycle transitions are serialized, but the lifecycle lock is released
 * before invoking subsystem code or waiting for kernel-owned schedulers. This
 * lets a callback safely coordinate with a worker that reads kernel state.</p>
 */
public final class NodeKernel implements AutoCloseable {
    private final List<Subsystem> subsystems;
    private final SubsystemContext context;
    private final List<Subsystem> initialized = new ArrayList<>();
    private final List<Subsystem> started = new ArrayList<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock(true);
    private final Condition transitionCompleted = lifecycleLock.newCondition();
    private KernelState state = KernelState.CREATED;
    private LifecycleTransition transition = LifecycleTransition.NONE;
    private Thread transitionOwner;
    private boolean closeRequested;
    private boolean closed;

    public NodeKernel(List<Subsystem> subsystems, SubsystemContext context) {
        this.subsystems = List.copyOf(Objects.requireNonNull(subsystems, "subsystems"));
        this.context = Objects.requireNonNull(context, "context");
    }

    public void start() {
        boolean initialize;
        List<Subsystem> initializedForStart;
        List<Subsystem> startedThisCycle = new ArrayList<>();

        lifecycleLock.lock();
        try {
            awaitIdleTransition("start");
            if (closed || closeRequested) {
                throw new IllegalStateException("Cannot start a closed kernel");
            }
            if (state == KernelState.RUNNING) {
                return;
            }
            if (state != KernelState.CREATED && state != KernelState.STOPPED) {
                throw new IllegalStateException("Cannot start kernel from state " + state);
            }

            initialize = state == KernelState.CREATED;
            initializedForStart = initialize ? new ArrayList<>() : new ArrayList<>(initialized);
            beginTransition(LifecycleTransition.STARTING);
            if (!initialize) {
                state = KernelState.STARTING;
            }
        } finally {
            lifecycleLock.unlock();
        }

        Throwable failure = null;
        try {
            if (initialize) {
                for (Subsystem subsystem : subsystems) {
                    // init() may acquire resources before it reports failure.
                    // Publish rollback ownership first so the attempted
                    // subsystem is closed along with earlier successful ones.
                    initializedForStart.add(subsystem);
                    subsystem.init(context);
                }
                updateTransitionState(KernelState.INITIALIZED);
                updateTransitionState(KernelState.STARTING);
            }
            for (Subsystem subsystem : initializedForStart) {
                subsystem.start();
                startedThisCycle.add(subsystem);
            }
        } catch (Throwable startupFailure) {
            updateTransitionState(KernelState.FAILED);
            failure = startupFailure(startupFailure);
            failure = stopReverse(startedThisCycle, failure, "Kernel startup failed");
            failure = closeReverse(initializedForStart, failure, "Kernel startup failed");
        }

        lifecycleLock.lock();
        try {
            initialized.clear();
            started.clear();
            if (failure == null) {
                initialized.addAll(initializedForStart);
                started.addAll(startedThisCycle);
                state = KernelState.RUNNING;
            } else {
                state = KernelState.FAILED;
            }
            finishTransition();
        } finally {
            lifecycleLock.unlock();
        }

        rethrow(failure);
    }

    public void stop() {
        List<Subsystem> startedForStop;

        lifecycleLock.lock();
        try {
            awaitIdleTransition("stop");
            if (state == KernelState.CREATED || state == KernelState.STOPPED
                    || state == KernelState.FAILED) {
                return;
            }
            startedForStop = new ArrayList<>(started);
            beginTransition(LifecycleTransition.STOPPING);
            state = KernelState.STOPPING;
        } finally {
            lifecycleLock.unlock();
        }

        Throwable failure = stopReverse(startedForStop, null, "Kernel stop failed");

        lifecycleLock.lock();
        try {
            started.clear();
            state = failure == null ? KernelState.STOPPED : KernelState.FAILED;
            finishTransition();
        } finally {
            lifecycleLock.unlock();
        }

        rethrow(failure);
    }

    @Override
    public void close() {
        List<Subsystem> startedForStop;
        List<Subsystem> ownedForClose;
        boolean stopBeforeClose;

        lifecycleLock.lock();
        try {
            if (closed && transition == LifecycleTransition.NONE) {
                return;
            }
            if (transitionOwner == Thread.currentThread()) {
                throw reentrantTransition("close");
            }
            closeRequested = true;
            awaitIdleTransition("close");
            if (closed) {
                return;
            }

            KernelState stateBeforeClose = state;
            stopBeforeClose = stateBeforeClose != KernelState.STOPPED
                    && stateBeforeClose != KernelState.CREATED;
            startedForStop = stopBeforeClose ? new ArrayList<>(started) : List.of();
            ownedForClose = stateBeforeClose == KernelState.CREATED && initialized.isEmpty()
                    ? new ArrayList<>(subsystems) : new ArrayList<>(initialized);
            beginTransition(LifecycleTransition.CLOSING);
            closed = true;
            if (stopBeforeClose) {
                state = KernelState.STOPPING;
            }
        } finally {
            lifecycleLock.unlock();
        }

        Throwable failure = null;
        if (stopBeforeClose) {
            failure = stopReverse(startedForStop, null, "Kernel close failed");
        }
        failure = closeReverse(ownedForClose, failure, "Kernel close failed");
        try {
            context.schedulers().close();
        } catch (Throwable schedulerFailure) {
            failure = recordFailure(failure, schedulerFailure, "Kernel close failed");
        }

        lifecycleLock.lock();
        try {
            started.clear();
            initialized.clear();
            state = failure == null ? KernelState.STOPPED : KernelState.FAILED;
            finishTransition();
        } finally {
            lifecycleLock.unlock();
        }

        rethrow(failure);
    }

    public KernelState state() {
        lifecycleLock.lock();
        try {
            return state;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public List<SubsystemHealth> health() {
        List<SubsystemHealth> result = new ArrayList<>();
        for (Subsystem subsystem : subsystems) {
            try {
                result.add(subsystem.health());
            } catch (Throwable t) {
                LifecycleFailures.rethrowIfProcessFatal(t);
                result.add(SubsystemHealth.down(
                        subsystem.name(), t.getClass().getName()));
            }
        }
        return List.copyOf(result);
    }

    public <T extends Subsystem> Optional<T> subsystem(Class<T> type) {
        for (Subsystem subsystem : subsystems) {
            if (type.isInstance(subsystem)) {
                return Optional.of(type.cast(subsystem));
            }
        }
        return Optional.empty();
    }

    public List<Subsystem> subsystems() {
        return subsystems;
    }

    private void awaitIdleTransition(String requestedOperation) {
        while (transition != LifecycleTransition.NONE) {
            if (transitionOwner == Thread.currentThread()) {
                throw reentrantTransition(requestedOperation);
            }
            transitionCompleted.awaitUninterruptibly();
        }
    }

    private IllegalStateException reentrantTransition(String requestedOperation) {
        return new IllegalStateException("Cannot " + requestedOperation + " kernel while "
                + transition.name().toLowerCase() + " it");
    }

    private void beginTransition(LifecycleTransition nextTransition) {
        transition = nextTransition;
        transitionOwner = Thread.currentThread();
    }

    private void finishTransition() {
        transition = LifecycleTransition.NONE;
        transitionOwner = null;
        transitionCompleted.signalAll();
    }

    private void updateTransitionState(KernelState nextState) {
        lifecycleLock.lock();
        try {
            state = nextState;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private static Throwable stopReverse(
            List<Subsystem> startedSubsystems,
            Throwable failure,
            String failureMessage
    ) {
        Throwable outcome = failure;
        for (int i = startedSubsystems.size() - 1; i >= 0; i--) {
            Subsystem subsystem = startedSubsystems.get(i);
            try {
                subsystem.stop();
            } catch (Throwable stopFailure) {
                outcome = recordFailure(outcome, stopFailure, failureMessage);
            }
        }
        return outcome;
    }

    private static Throwable closeReverse(
            List<Subsystem> initializedSubsystems,
            Throwable failure,
            String failureMessage
    ) {
        Throwable outcome = failure;
        for (int i = initializedSubsystems.size() - 1; i >= 0; i--) {
            Subsystem subsystem = initializedSubsystems.get(i);
            try {
                subsystem.close();
            } catch (Throwable closeFailure) {
                outcome = recordFailure(outcome, closeFailure, failureMessage);
            }
        }
        return outcome;
    }

    private static Throwable startupFailure(Throwable failure) {
        if (failure instanceof Error) {
            return failure;
        }
        return new KernelLifecycleException("Kernel startup failed", failure);
    }

    private static Throwable recordFailure(Throwable current, Throwable next, String failureMessage) {
        Objects.requireNonNull(next, "next");
        if (current == null) {
            if (next instanceof Error) {
                return next;
            }
            KernelLifecycleException failure = new KernelLifecycleException(failureMessage);
            failure.addSuppressed(next);
            return failure;
        }
        return LifecycleFailures.merge(current, next);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure != null) {
            throw new KernelLifecycleException("Kernel lifecycle failed", failure);
        }
    }

    private enum LifecycleTransition {
        NONE,
        STARTING,
        STOPPING,
        CLOSING
    }
}
