package com.bloxbean.cardano.yano.runtime.maintenance;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Coordinates normal runtime reads with exclusive maintenance operations that
 * can replace shared storage handles.
 */
public final class RuntimeMaintenanceGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AtomicReference<String> activeReason = new AtomicReference<>();
    private final AtomicReference<Degradation> degradation = new AtomicReference<>();

    public ReadLease enterRead(String operation) {
        lock.readLock().lock();
        return new ReadLease(operation);
    }

    public MaintenanceLease enterMaintenance(String reason) {
        String resolvedReason = reason == null || reason.isBlank() ? "maintenance" : reason;
        lock.writeLock().lock();
        String previousReason = activeReason.get();
        activeReason.set(resolvedReason);
        return new MaintenanceLease(resolvedReason, previousReason);
    }

    public boolean isMaintenanceActive() {
        return lock.isWriteLocked();
    }

    public String activeReason() {
        return activeReason.get();
    }

    public Degradation degradation() {
        return degradation.get();
    }

    public boolean isDegraded() {
        return degradation.get() != null;
    }

    public void markDegraded(String operation, String message, Throwable cause) {
        String resolvedOperation = operation == null || operation.isBlank() ? "maintenance" : operation;
        String resolvedMessage = message == null || message.isBlank()
                ? "Runtime requires restart after failed maintenance"
                : message;
        String causeMessage = cause != null ? cause.toString() : null;
        degradation.set(new Degradation(
                resolvedOperation,
                resolvedMessage,
                causeMessage,
                System.currentTimeMillis()));
    }

    public void clearDegraded() {
        degradation.set(null);
    }

    public void clearDegradedForOperation(String operation) {
        String resolvedOperation = operation == null || operation.isBlank() ? "maintenance" : operation;
        degradation.updateAndGet(existing ->
                existing != null && existing.operation().equals(resolvedOperation) ? null : existing);
    }

    /**
     * Captures a maintenance failure that leaves runtime reads degraded until
     * the affected operation is cleared or the process restarts.
     */
    public record Degradation(String operation, String message, String cause, long timestampMillis) {
        public Degradation {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation must not be blank");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }

    /**
     * Read-side lease held while a request is using runtime storage handles.
     */
    public final class ReadLease implements AutoCloseable {
        private final String operation;
        private boolean closed;

        private ReadLease(String operation) {
            this.operation = operation;
        }

        public String operation() {
            return operation;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.readLock().unlock();
            }
        }
    }

    /**
     * Exclusive lease held while maintenance replaces or rewinds mutable
     * runtime storage.
     */
    public final class MaintenanceLease implements AutoCloseable {
        private final String reason;
        private final String previousReason;
        private boolean closed;

        private MaintenanceLease(String reason, String previousReason) {
            this.reason = Objects.requireNonNull(reason, "reason");
            this.previousReason = previousReason;
        }

        public String reason() {
            return reason;
        }

        public void markDegraded(String message, Throwable cause) {
            RuntimeMaintenanceGate.this.markDegraded(reason, message, cause);
        }

        public void clearDegraded() {
            RuntimeMaintenanceGate.this.clearDegradedForOperation(reason);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                activeReason.compareAndSet(reason, previousReason);
                lock.writeLock().unlock();
            }
        }
    }
}
