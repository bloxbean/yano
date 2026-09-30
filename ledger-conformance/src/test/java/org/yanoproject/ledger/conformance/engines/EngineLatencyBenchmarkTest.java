package org.yanoproject.ledger.conformance.engines;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yanoproject.ledger.conformance.ConformanceSettings;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.scripteval.phase2.JulcScriptPhaseEvaluator;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-057 Phase E latency benchmark: warm per-transaction validation latency of {@code java-julc}, {@code java-scalus}
 * and (in {@code -PwithAmaru=true} builds) {@code amaru} on the same fixed corpus, the Amaru scenarios at protocol
 * version 10 and later (the one PV 9 scenario is left out: Amaru refuses it without validating).
 *
 * <p>Each engine is created once per network and ledger constants and reused, as the node reuses its engines, so a
 * sample is one {@code validate} call: rule {@code LEDGER}, origin {@code SYNC}, the case's in-memory view. The Java
 * engines run on the calling thread; {@code amaru} hands each call to its instance pool's worker thread, as in the
 * node, with {@code phase2 = full} (Amaru runs the scripts). Warm-up passes are not sampled; the first of them is
 * reported as the cold pass.</p>
 *
 * <pre>
 * ./gradlew :ledger-conformance:test --tests '*EngineLatencyBenchmarkTest' -PengineBenchmark=true \
 *     -PamaruScenariosDir=&lt;amaru clone at the pinned tag&gt; -PwithAmaru=true -PamaruWasm=&lt;module.wasm&gt;
 * </pre>
 *
 * <p>{@code -PengineBenchmarkPasses} (default 5) and {@code -PengineBenchmarkWarmup} (default 2) set the passes. The
 * table goes to stdout and to {@code build/conformance/engine-latency.md}.</p>
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "yano.engine.benchmark", matches = "true")
class EngineLatencyBenchmarkTest {

    private static final int FIRST_AMARU_MAJOR = 10;

    @Test
    void warmLatencyPerEngine() {
        List<ConformanceCase> cases = ScenarioCases.cases().orElse(List.of()).stream()
                .filter(c -> c.env().protocolMajor() >= FIRST_AMARU_MAJOR)
                .toList();
        Assumptions.assumeFalse(cases.isEmpty(), "the Amaru scenarios are not configured (-PamaruScenariosDir)");
        int warmup = Math.max(1, Integer.getInteger("yano.engine.benchmark.warmup", 2));
        int passes = Math.max(1, Integer.getInteger("yano.engine.benchmark.passes", 5));

        List<ConformanceEngine> engines = new ArrayList<>(List.of(
                new ReusedJavaEngine("java-julc", h -> JavaViewEngine.create(new JulcScriptPhaseEvaluator(h))),
                new ReusedJavaEngine("java-scalus",
                        h -> JavaViewEngine.createScalus(new ScalusScriptPhaseEvaluator(h)))));
        BaselineEngines.amaru().ifPresent(engines::add);

        StringBuilder table = new StringBuilder()
                .append("# Engine latency (ADR-057 Phase E)\n\n")
                .append(String.format(Locale.ROOT, "%s %s, %s %s, %d processors, max heap %d MiB; %d cases "
                                + "(Amaru scenarios, PV >= 10), %d warm-up + %d sampled passes%n%n",
                        System.getProperty("java.vm.name"), System.getProperty("java.version"),
                        System.getProperty("os.name"), System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20,
                        cases.size(), warmup, passes))
                .append("| Engine | samples | cold pass mean ms | mean ms | p50 ms | p90 ms | p99 ms | max ms "
                        + "| valid | crashes |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ConformanceEngine engine : engines) {
            table.append(measure(engine, cases, warmup, passes));
        }
        String report = table.toString();
        System.out.println(report);
        Path written = ConformanceSettings.write("engine-latency.md", "engine.benchmark.file", report);
        System.out.println("Written to " + written.toAbsolutePath());
    }

    private static String measure(ConformanceEngine engine, List<ConformanceCase> cases, int warmup, int passes) {
        double coldMs = 0;
        for (int pass = 0; pass < warmup; pass++) {
            long started = System.nanoTime();
            for (ConformanceCase testCase : cases) {
                validate(engine, testCase);
            }
            if (pass == 0) {
                coldMs = (System.nanoTime() - started) / 1e6 / cases.size();
            }
        }
        long[] nanos = new long[cases.size() * passes];
        int n = 0;
        int valid = 0;
        int crashes = 0;
        for (int pass = 0; pass < passes; pass++) {
            for (ConformanceCase testCase : cases) {
                long start = System.nanoTime();
                Observation observation = validate(engine, testCase);
                nanos[n++] = System.nanoTime() - start;
                if (pass == 0) {
                    valid += observation.valid() ? 1 : 0;
                    crashes += observation.failures().stream()
                            .anyMatch(f -> Observation.CRASH.equals(f.rule())) ? 1 : 0;
                }
            }
        }
        assertThat(n).isPositive();
        Arrays.sort(nanos);
        double mean = Arrays.stream(nanos).average().orElse(0) / 1e6;
        return String.format(Locale.ROOT, "| `%s` | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %d/%d | %d |%n",
                engine.name(), nanos.length, coldMs, mean, percentile(nanos, 0.50), percentile(nanos, 0.90),
                percentile(nanos, 0.99), nanos[nanos.length - 1] / 1e6, valid, cases.size(), crashes);
    }

    private static Observation validate(ConformanceEngine engine, ConformanceCase testCase) {
        try {
            return engine.validate(testCase);
        } catch (Exception | LinkageError | StackOverflowError e) {
            return Observation.crash(e);
        }
    }

    /** Nearest-rank percentile of sorted samples, in milliseconds. */
    private static double percentile(long[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))] / 1e6;
    }

    /**
     * A Java engine created once per network and constants ({@link JavaViewEngine} creates one per case, which the
     * baseline report's timing includes).
     */
    private static final class ReusedJavaEngine implements ConformanceEngine {

        private final String name;
        private final Function<ForecastHorizon, JavaLedgerValidationEngine> factory;
        private final Map<String, JavaLedgerValidationEngine> engines = new ConcurrentHashMap<>();

        ReusedJavaEngine(String name, Function<ForecastHorizon, JavaLedgerValidationEngine> factory) {
            this.name = name;
            this.factory = factory;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + ", reused per network and constants";
        }

        @Override
        public Observation validate(ConformanceCase testCase) {
            JavaLedgerValidationEngine engine = engines.computeIfAbsent(
                    testCase.network() + "|" + testCase.constants(), key -> {
                        ForecastHorizon horizon = ForecastHorizon.of(testCase.network()::stabilityWindow,
                                CaseTiming.geometry(testCase.network().eras()));
                        JavaLedgerValidationEngine created = factory.apply(horizon);
                        return testCase.constants().isNone() ? created
                                : created.withConstants(JavaViewEngine.constants(testCase.constants()));
                    });
            return Observation.of(engine.validate(new TxValidationRequest(testCase.txCbor(), testCase.view(),
                    testCase.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null)));
        }
    }
}
