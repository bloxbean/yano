package com.bloxbean.cardano.yano.runtime.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Small lifecycle owner for an ordered subsystem assembly.
 */
public final class NodeKernel implements AutoCloseable {
    private final List<Subsystem> subsystems;
    private final SubsystemContext context;
    private final List<Subsystem> initialized = new ArrayList<>();
    private final List<Subsystem> started = new ArrayList<>();
    private KernelState state = KernelState.CREATED;
    private boolean closed;

    public NodeKernel(List<Subsystem> subsystems, SubsystemContext context) {
        this.subsystems = List.copyOf(Objects.requireNonNull(subsystems, "subsystems"));
        this.context = Objects.requireNonNull(context, "context");
    }

    public synchronized void start() {
        if (state == KernelState.RUNNING) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Cannot start a closed kernel");
        }
        if (state != KernelState.CREATED && state != KernelState.STOPPED) {
            throw new IllegalStateException("Cannot start kernel from state " + state);
        }

        started.clear();
        try {
            if (state == KernelState.CREATED) {
                initialized.clear();
                for (Subsystem subsystem : subsystems) {
                    subsystem.init(context);
                    initialized.add(subsystem);
                }
                state = KernelState.INITIALIZED;
            }
            state = KernelState.STARTING;
            for (Subsystem subsystem : initialized) {
                subsystem.start();
                started.add(subsystem);
            }
            state = KernelState.RUNNING;
        } catch (Throwable t) {
            state = KernelState.FAILED;
            KernelLifecycleException failure = new KernelLifecycleException("Kernel startup failed", t);
            stopStarted(failure);
            closeInitialized(failure);
            started.clear();
            initialized.clear();
            throw failure;
        }
    }

    public synchronized void stop() {
        if (state == KernelState.CREATED || state == KernelState.STOPPED) {
            return;
        }
        state = KernelState.STOPPING;
        KernelLifecycleException failure = new KernelLifecycleException("Kernel stop failed");
        stopStarted(failure);
        started.clear();
        state = failure.getSuppressed().length == 0 ? KernelState.STOPPED : KernelState.FAILED;
        if (failure.getSuppressed().length > 0) {
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        KernelLifecycleException failure = new KernelLifecycleException("Kernel close failed");
        try {
            if (state != KernelState.STOPPED && state != KernelState.CREATED) {
                try {
                    stop();
                } catch (KernelLifecycleException e) {
                    failure.addSuppressed(e);
                }
            }
        } finally {
            if (state == KernelState.CREATED && initialized.isEmpty()) {
                closeAll(failure);
            } else {
                closeInitialized(failure);
            }
            started.clear();
            initialized.clear();
            context.schedulers().close();
            closed = true;
        }
        state = failure.getSuppressed().length == 0 ? KernelState.STOPPED : KernelState.FAILED;
        if (failure.getSuppressed().length > 0) {
            throw failure;
        }
    }

    public synchronized KernelState state() {
        return state;
    }

    public List<SubsystemHealth> health() {
        List<SubsystemHealth> result = new ArrayList<>();
        for (Subsystem subsystem : subsystems) {
            try {
                result.add(subsystem.health());
            } catch (Throwable t) {
                result.add(SubsystemHealth.down(subsystem.name(), t.toString()));
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

    private void stopStarted(KernelLifecycleException failure) {
        List<Subsystem> reverse = new ArrayList<>(started);
        Collections.reverse(reverse);
        for (Subsystem subsystem : reverse) {
            try {
                subsystem.stop();
            } catch (Throwable t) {
                failure.addSuppressed(t);
            }
        }
    }

    private void closeInitialized(KernelLifecycleException failure) {
        List<Subsystem> reverse = new ArrayList<>(initialized);
        Collections.reverse(reverse);
        for (Subsystem subsystem : reverse) {
            try {
                subsystem.close();
            } catch (Throwable t) {
                failure.addSuppressed(t);
            }
        }
    }

    private void closeAll(KernelLifecycleException failure) {
        List<Subsystem> reverse = new ArrayList<>(subsystems);
        Collections.reverse(reverse);
        for (Subsystem subsystem : reverse) {
            try {
                subsystem.close();
            } catch (Throwable t) {
                failure.addSuppressed(t);
            }
        }
    }
}
