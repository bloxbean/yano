package com.bloxbean.cardano.yano.appchain.conformance;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adversarial fixture seam proving that returned products, not only their
 * factories, cross the catalog facade. A provider poisons its callback TCCL
 * immediately before returning. The first product callback poisons it again;
 * every later callback rejects a missing TCCL. A correctly mediated call is
 * restored after each boundary, while a raw returned product leaks the first
 * poison into its caller and fails on its next callback.
 */
final class ConformanceTcclProbe {
    private ConformanceTcclProbe() {
    }

    static void poisonProviderCallback() {
        Thread.currentThread().setContextClassLoader(null);
    }

    static void requireCatalogFacade(String callback) {
        if (Thread.currentThread().getContextClassLoader() == null) {
            throw new IllegalStateException(
                    callback + " bypassed the catalog-owned plugin facade");
        }
    }

    static void productCallback(AtomicBoolean firstCallback, String callback) {
        if (firstCallback.compareAndSet(true, false)) {
            Thread.currentThread().setContextClassLoader(null);
            return;
        }
        requireCatalogFacade(callback);
    }
}
