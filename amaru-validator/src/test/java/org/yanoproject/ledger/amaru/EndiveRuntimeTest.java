package org.yanoproject.ledger.amaru;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;
import org.yanoproject.ledger.amaru.wire.AmaruRequest;
import org.yanoproject.ledger.amaru.wire.AmaruRequestEncoder;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import run.endive.runtime.WasmInterruptedException;

import static org.assertj.core.api.Assertions.assertThat;

/** Endive runtime properties the engine relies on (ADR-057 open question 3, trap recovery cost). */
class EndiveRuntimeTest {

    private static final long STACK = AmaruEngineConfig.DEFAULT_WORKER_STACK_SIZE;

    /**
     * An interrupt raised while {@code validate} is already running inside the guest stops it: Endive
     * checks the interrupt flag on calls and backward branches of AOT-compiled code, not only at the
     * export's entry. A second thread interrupts the worker a random 0–2 ms after the export was entered
     * (the heaviest scenario runs for longer); the {@link WasmInterruptedException} must come out of the
     * {@code validate} export itself, from more nested compiled frames than an interrupt at its entry.
     */
    @Test
    void anInterruptStopsAGuestMidCall() throws Exception {
        byte[] request = heaviestRequest();
        Throwable atEntry = runInterrupted(request, -1);
        assertThat(atEntry).isInstanceOf(WasmInterruptedException.class);
        assertThat(insideValidateExport(atEntry)).isTrue();
        int entryDepth = compiledFrames(atEntry);

        int deepest = 0;
        for (int attempt = 0; attempt < 50 && deepest <= entryDepth; attempt++) {
            Throwable thrown = runInterrupted(request, ThreadLocalRandom.current().nextLong(50_000, 2_000_000));
            if (thrown instanceof WasmInterruptedException && insideValidateExport(thrown)) {
                deepest = Math.max(deepest, compiledFrames(thrown));
            }
        }
        System.out.println("Interrupted guest stacks: " + entryDepth + " compiled wasm frame(s) at the export's "
                + "entry, " + deepest + " at the deepest mid-call interrupt");
        assertThat(deepest).isGreaterThan(entryDepth);
    }

    /**
     * Runs one {@code validate} on a fresh instance and interrupts it {@code delayNanos} after the export
     * is entered (a negative delay: just before it is entered).
     *
     * @return what the call threw, or null when it completed first
     */
    private static Throwable runInterrupted(byte[] request, long delayNanos) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().stackSize(STACK).unstarted(() -> {
            try (WasmAmaruInstance instance = new WasmAmaruInstance(ScenarioSupport.MAX_MEMORY_PAGES)) {
                instance.validate(request); // warm this instance's machine
                Thread self = Thread.currentThread();
                instance.validate(request, () -> {
                    if (delayNanos < 0) {
                        self.interrupt();
                    } else {
                        Thread.ofVirtual().start(() -> {
                            LockSupport.parkNanos(delayNanos);
                            self.interrupt();
                        });
                    }
                });
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        assertThat(worker.join(Duration.ofSeconds(30))).as("worker stopped").isTrue();
        return thrown.get();
    }

    private static boolean insideValidateExport(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().equals(WasmAmaruInstance.class.getName())) {
                // The first Yano frame below the guest must be validate, not the alloc/dealloc helpers.
                return frame.getMethodName().equals("validate");
            }
        }
        return false;
    }

    private static int compiledFrames(Throwable t) {
        return (int) Arrays.stream(t.getStackTrace())
                .filter(frame -> frame.getClassName().contains("AmaruValidatorModuleMachine"))
                .count();
    }

    /** Cost of replacing a discarded instance (instantiation plus {@code _initialize}), warm. */
    @Test
    void instantiationCost() throws Exception {
        long[] nanos = new long[30];
        Thread worker = Thread.ofPlatform().stackSize(STACK).start(() -> {
            for (int i = 0; i < 10; i++) {
                new WasmAmaruInstance(ScenarioSupport.MAX_MEMORY_PAGES).close();
            }
            for (int i = 0; i < nanos.length; i++) {
                long start = System.nanoTime();
                new WasmAmaruInstance(ScenarioSupport.MAX_MEMORY_PAGES).close();
                nanos[i] = System.nanoTime() - start;
            }
        });
        worker.join();
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT, "Amaru instance creation (warm, %d samples): p50 %.3f ms, max %.3f ms%n",
                nanos.length, nanos[nanos.length / 2] / 1e6, nanos[nanos.length - 1] / 1e6);
        assertThat(nanos[0]).isPositive();
    }

    /** The scenario whose {@code validate} takes longest (a Plutus one), as a reference request. */
    private static byte[] heaviestRequest() throws Exception {
        List<AmaruScenario> scenarios = AmaruScenarioLoader.fromEnvironment().loadAll();
        List<byte[]> requests = scenarios.stream()
                .map(s -> AmaruRequestEncoder.encode(ScenarioSupport.referenceRequest(s, AmaruRequest.Mode.FULL)))
                .toList();
        AtomicReference<byte[]> heaviest = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().stackSize(STACK).start(() -> {
            try (WasmAmaruInstance instance = new WasmAmaruInstance(ScenarioSupport.MAX_MEMORY_PAGES)) {
                requests.forEach(instance::validate);
                long worst = -1;
                for (byte[] request : requests) {
                    long start = System.nanoTime();
                    instance.validate(request);
                    long took = System.nanoTime() - start;
                    if (took > worst) {
                        worst = took;
                        heaviest.set(request);
                    }
                }
            }
        });
        worker.join();
        return heaviest.get();
    }
}
