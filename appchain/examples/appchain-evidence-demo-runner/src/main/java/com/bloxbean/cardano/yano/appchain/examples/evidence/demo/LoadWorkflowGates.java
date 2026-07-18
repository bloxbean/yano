package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Fair load-only lanes for quota-sensitive composite workflow submissions. */
final class LoadWorkflowGates {
    private final Semaphore release;
    private final Semaphore notification;

    LoadWorkflowGates() {
        this(1);
    }

    LoadWorkflowGates(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("load workflow capacity must be positive");
        }
        release = new Semaphore(capacity, true);
        notification = new Semaphore(capacity, true);
    }

    <T> T release(Supplier<T> action) {
        return within(release, action);
    }

    void notification(Runnable action) {
        within(notification, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T within(Semaphore gate, Supplier<T> action) {
        try {
            gate.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
        try {
            return action.get();
        } finally {
            gate.release();
        }
    }
}
