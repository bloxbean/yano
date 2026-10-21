package org.yanoproject.ledger.amaru;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yanoproject.ledger.amaru.generated.AmaruValidatorModule;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;
import org.yanoproject.ledger.amaru.wire.AmaruRequest;
import org.yanoproject.ledger.amaru.wire.AmaruRequestEncoder;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-057 Phase E startup and memory figures for the Amaru engine, measured in a fresh test JVM:
 * <ol>
 *   <li><b>Cold start.</b> Parsing the stripped {@code .meta} module ({@code AmaruValidatorModule.load()}), then the
 *       first instance (loading the build-time AOT machine classes, instantiation, {@code _initialize}), then the
 *       first {@code validate} on it and the first pass over the corpus (JIT still cold).</li>
 *   <li><b>Shared cost.</b> Metaspace and heap retained after the first instance: the AOT classes and the parsed
 *       module, paid once per JVM.</li>
 *   <li><b>Warm instantiation</b> (what replacing a trapped instance costs).</li>
 *   <li><b>Per-instance memory.</b> Retained heap per instance, fresh and after one pass over the corpus (each
 *       instance's {@code ByteArrayMemory} is on the heap and grows to its working size). The 256 MiB worker stack is
 *       reserved address space, committed only as deep as a call goes, and is not counted.</li>
 * </ol>
 *
 * <p>Run it alone, so nothing else loads the module first:</p>
 * <pre>
 * ./gradlew :amaru-validator:test --tests '*AmaruFootprintBenchmarkTest' -PengineBenchmark=true \
 *     -PwithAmaru=true -PamaruWasm=&lt;module.wasm&gt; -PamaruScenariosDir=&lt;amaru clone at the pinned tag&gt;
 * </pre>
 *
 * <p>{@code -PengineBenchmarkInstances} (default 8) sets how many instances the memory figure holds at once. Heap
 * figures come from {@code MemoryMXBean} after explicit GCs and are approximate. The results go to stdout and to
 * {@code build/benchmark/amaru-footprint.md}.</p>
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "yano.engine.benchmark", matches = "true")
class AmaruFootprintBenchmarkTest {

    private static final long STACK = AmaruEngineConfig.DEFAULT_WORKER_STACK_SIZE;
    private static final int PAGES = AmaruEngineConfig.DEFAULT_MAX_MEMORY_PAGES;
    private static final int INSTANTIATION_SAMPLES = 30;

    @Test
    void startupAndMemoryPerInstance() throws Exception {
        int instances = Math.max(1, Integer.getInteger("yano.engine.benchmark.instances", 8));
        // Everything that does not touch Endive first: the corpus and the reference requests.
        List<byte[]> requests = AmaruScenarioLoader.fromEnvironment().loadAll().stream()
                .map(s -> AmaruRequestEncoder.encode(ScenarioSupport.referenceRequest(s, AmaruRequest.Mode.FULL)))
                .toList();

        long heap0 = settledHeap();
        long meta0 = metaspace();

        long started = System.nanoTime();
        AmaruValidatorModule.load();
        double moduleLoadMs = (System.nanoTime() - started) / 1e6;

        double[] cold = new double[3];
        AtomicReference<WasmAmaruInstance> first = new AtomicReference<>();
        onWorker(() -> {
            long t = System.nanoTime();
            WasmAmaruInstance instance = new WasmAmaruInstance(PAGES);
            cold[0] = (System.nanoTime() - t) / 1e6;
            t = System.nanoTime();
            instance.validate(requests.getFirst());
            cold[1] = (System.nanoTime() - t) / 1e6;
            t = System.nanoTime();
            requests.forEach(instance::validate);
            cold[2] = (System.nanoTime() - t) / 1e6;
            first.set(instance);
        });
        long heapShared = settledHeap() - heap0;
        long metaShared = metaspace() - meta0;
        first.get().close();
        first.set(null);

        long[] nanos = new long[INSTANTIATION_SAMPLES];
        onWorker(() -> {
            for (int i = 0; i < 10; i++) {
                new WasmAmaruInstance(PAGES).close();
            }
            for (int i = 0; i < nanos.length; i++) {
                long t = System.nanoTime();
                new WasmAmaruInstance(PAGES).close();
                nanos[i] = System.nanoTime() - t;
            }
        });
        Arrays.sort(nanos);

        List<WasmAmaruInstance> held = new ArrayList<>();
        long heapBefore = settledHeap();
        onWorker(() -> {
            for (int i = 0; i < instances; i++) {
                held.add(new WasmAmaruInstance(PAGES));
            }
        });
        long heapFresh = settledHeap();
        onWorker(() -> held.forEach(instance -> requests.forEach(instance::validate)));
        long heapWarm = settledHeap();
        assertThat(held).hasSize(instances);
        held.forEach(WasmAmaruInstance::close);

        String report = String.format(Locale.ROOT, """
                        # Amaru engine footprint (ADR-057 Phase E)

                        %s %s, %s %s, %d processors, max heap %d MiB; %d reference requests (full mode)

                        | Figure | Value |
                        |---|---:|
                        | Cold: parse the .meta module | %.1f ms |
                        | Cold: first instance (AOT classes, instantiation, _initialize) | %.1f ms |
                        | Cold: first validate on it | %.1f ms |
                        | Cold: first pass over the corpus, mean per request | %.3f ms |
                        | Shared, once per JVM: metaspace after the first instance | %.1f MiB |
                        | Shared, once per JVM: retained heap after the first instance | %.1f MiB |
                        | Warm instantiation p50 / max (%d samples) | %.3f / %.3f ms |
                        | Retained heap per instance, fresh (%d held) | %.1f MiB |
                        | Retained heap per instance, after one corpus pass (%d held) | %.1f MiB |
                        """,
                System.getProperty("java.vm.name"), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20, requests.size(),
                moduleLoadMs, cold[0], cold[1], cold[2] / requests.size(), mib(metaShared), mib(heapShared),
                nanos.length, nanos[nanos.length / 2] / 1e6, nanos[nanos.length - 1] / 1e6,
                instances, mib(heapFresh - heapBefore) / instances,
                instances, mib(heapWarm - heapBefore) / instances);
        System.out.println(report);
        String dir = System.getProperty("yano.engine.benchmark.dir", "build/benchmark");
        try {
            Path target = Path.of(dir, "amaru-footprint.md");
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
            System.out.println("Written to " + target.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs {@code work} on a platform thread with the engine's worker stack, as the instance pool does. */
    private static void onWorker(Runnable work) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().stackSize(STACK).start(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.join();
        if (failure.get() != null) {
            throw new AssertionError("benchmark step failed", failure.get());
        }
    }

    private static long settledHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long metaspace() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().equals("Metaspace"))
                .mapToLong(pool -> pool.getUsage().getUsed())
                .sum();
    }

    private static double mib(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }
}
