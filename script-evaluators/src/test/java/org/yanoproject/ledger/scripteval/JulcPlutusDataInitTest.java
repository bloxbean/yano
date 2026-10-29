package org.yanoproject.ledger.scripteval;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.scripteval.phase2.JulcScriptPhaseEvaluator;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * julc #266: concurrent first use of julc's {@link PlutusData} types deadlocks in class initialization. Each case loads
 * julc in a fresh class loader, so its classes start uninitialized, then initializes the PlutusData types from 8
 * threads released together. A deadlocked round leaves its daemon threads and loader stuck for the rest of the test
 * JVM, which is harmless; the negative control stops at the first one.
 */
class JulcPlutusDataInitTest {

    private static final String[] TYPES = {"ConstrData", "ListData", "IntData", "MapData", "BytesData"};
    private static final int THREADS = 8;

    @Test
    void concurrentFirstUseDeadlocksWithoutTheWorkaround() throws Exception {
        // Proves the race reproduces julc #266 (about 60% of rounds deadlock), so the cases below are meaningful.
        int rounds = 50;
        boolean deadlocked = false;
        for (int round = 0; round < rounds && !deadlocked; round++) {
            var loader = freshLoader(julcCore());
            deadlocked = !raceCompletes(loader);
            if (!deadlocked) {
                loader.close();
            }
        }
        assertTrue(deadlocked, "concurrent first use never deadlocked in " + rounds + " rounds, so julc #266 looks "
                + "fixed: remove JulcPlutusDataInit and its static-initializer callers");
    }

    @Test
    void ensureInitializedPreventsTheDeadlock() throws Exception {
        for (int round = 0; round < 10; round++) {
            try (var loader = freshLoader(location(JulcPlutusDataInit.class), julcCore())) {
                loader.loadClass(JulcPlutusDataInit.class.getName()).getMethod("ensureInitialized").invoke(null);
                assertTrue(raceCompletes(loader), "deadlocked after ensureInitialized in round " + round);
            }
        }
    }

    @Test
    void evaluatorEntryPointsApplyTheWorkaround() throws Exception {
        for (String entryPoint : new String[]{JulcScriptPhaseEvaluator.class.getName(),
                JulcTxEvaluator.class.getName()}) {
            for (int round = 0; round < 10; round++) {
                try (var loader = freshLoader(testClasspath())) {
                    Class.forName(entryPoint, true, loader);
                    assertTrue(raceCompletes(loader), "deadlocked after initializing " + entryPoint
                            + " in round " + round);
                }
            }
        }
    }

    /** Initializes the PlutusData types from {@link #THREADS} threads at once; false if they deadlock. */
    private static boolean raceCompletes(ClassLoader loader) throws InterruptedException {
        var start = new CyclicBarrier(THREADS);
        var done = new CountDownLatch(THREADS);
        var failure = new AtomicReference<Throwable>();
        for (int i = 0; i < THREADS; i++) {
            String type = PlutusData.class.getName() + "$" + TYPES[i % TYPES.length];
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    Class.forName(type, true, loader);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        boolean completed = done.await(5, TimeUnit.SECONDS);
        if (failure.get() != null) {
            throw new AssertionError("a racing thread failed instead of deadlocking", failure.get());
        }
        return completed;
    }

    private static URLClassLoader freshLoader(URL... urls) {
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static URL julcCore() {
        return location(PlutusData.class);
    }

    private static URL location(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    private static URL[] testClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> {
                    try {
                        return new File(path).toURI().toURL();
                    } catch (MalformedURLException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toArray(URL[]::new);
    }
}
