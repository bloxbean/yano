package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;

import java.util.Map;

/** Fork-only probe; its health snapshot intentionally never returns. */
public final class ConformanceHealthHangProbe {
    private ConformanceHealthHangProbe() {
    }

    public static void main(String[] args) {
        ClassLoader loader = ConformanceHealthHangProbe.class.getClassLoader();
        PluginHealthSource source = new ConformanceHealthProvider().create(
                new PluginHealthContext(ConformanceHealthProvider.ID, Map.of()));
        Thread.currentThread().setContextClassLoader(loader);
        source.checks();
        Thread.currentThread().setContextClassLoader(loader);
        Thread sampler = Thread.currentThread();
        Thread.ofPlatform().daemon(true).name("conformance-hang-interrupter").start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return;
            }
            sampler.interrupt();
        });
        source.snapshot();
        throw new AssertionError("HANG health snapshot unexpectedly returned");
    }
}
