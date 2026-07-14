package com.bloxbean.cardano.yano.runtime.plugins;

import java.util.Objects;

/**
 * Runs plugin-owned code with the catalog's shared class loader as the thread
 * context class loader and restores the caller's context on every exit path.
 */
final class PluginThreadContext {

    private PluginThreadContext() {
    }

    static ClassLoader effective(ClassLoader loader) {
        if (loader != null) {
            return loader;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : PluginThreadContext.class.getClassLoader();
    }

    static <T, X extends Throwable> T call(ClassLoader loader,
                                            ThrowingSupplier<T, X> callback) throws X {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(callback, "callback");
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return callback.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    static <X extends Throwable> void run(ClassLoader loader,
                                           ThrowingRunnable<X> callback) throws X {
        call(loader, () -> {
            callback.run();
            return null;
        });
    }

    @FunctionalInterface
    interface ThrowingSupplier<T, X extends Throwable> {
        T get() throws X;
    }

    @FunctionalInterface
    interface ThrowingRunnable<X extends Throwable> {
        void run() throws X;
    }
}
