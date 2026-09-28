package org.yanoproject.ledger.amaru;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;
import org.yanoproject.ledger.amaru.wire.AmaruRequest;
import org.yanoproject.ledger.amaru.wire.AmaruRequestEncoder;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.TxValidationRequest.Rule;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-057 Phase B gate: every Amaru scenario at the pinned tag, through the full Java engine path
 * (scenario loader → {@code InMemoryLedgerView} → {@link AmaruTransactionValidator} in {@code full} mode →
 * Endive AOT module), must get the expected verdict, rule and Haskell constructor.
 *
 * <p>Rule {@code LEDGER} and origin {@code SYNC}: the corpus exercises the {@code LEDGER} rule, and one
 * scenario is a valid {@code is_valid = false} transaction, which only shadow sync admits (ADR-056 §6).
 * One scenario runs at protocol version 9; the engine refuses it with {@code ENGINE.EraNotSupported}
 * (invariant 6), which is the expected outcome here.</p>
 */
class AmaruScenarioGateTest {

    private static final int LATENCY_PASSES = 5;

    @Test
    void everyScenarioMatchesThroughTheJavaEnginePath() {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();
        List<AmaruScenario> scenarios = loader.loadAll();
        assertThat(scenarios).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);

        List<String> mismatches = new ArrayList<>();
        int matched = 0;
        int refusedBelowConway = 0;
        for (AmaruScenario scenario : scenarios) {
            TxValidationOutcome outcome = ScenarioSupport.validate(ScenarioSupport.engine(scenario), scenario,
                    Rule.LEDGER, Origin.SYNC);
            String problem = mismatch(scenario, outcome);
            if (problem == null) {
                matched++;
                if (scenario.env().protocolMajor() < 10) {
                    refusedBelowConway++;
                }
            } else {
                mismatches.add(scenario.name() + ": " + problem);
            }
        }
        System.out.printf(Locale.ROOT, "Amaru scenarios through the Java engine: %d of %d matched "
                + "(%d refused below protocol version 10 by invariant 6)%n", matched, scenarios.size(),
                refusedBelowConway);
        assertThat(mismatches).as("scenario mismatches").isEmpty();

        // Warm latency over the corpus (JVM warm: the pass above is the warm-up). ADR-057 Phase E target:
        // p50 <= 2 ms, p99 <= 10 ms.
        long[] nanos = new long[scenarios.size() * LATENCY_PASSES];
        int n = 0;
        for (int pass = 0; pass < LATENCY_PASSES; pass++) {
            for (AmaruScenario scenario : scenarios) {
                AmaruTransactionValidator engine = ScenarioSupport.engine(scenario);
                long start = System.nanoTime();
                ScenarioSupport.validate(engine, scenario, Rule.LEDGER, Origin.SYNC);
                nanos[n++] = System.nanoTime() - start;
            }
        }
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT, "Amaru engine latency over %d validations (warm, full mode): "
                        + "p50 %.3f ms, p90 %.3f ms, p99 %.3f ms, max %.3f ms%n", nanos.length,
                nanos[nanos.length / 2] / 1e6, nanos[(int) (nanos.length * 0.90)] / 1e6,
                nanos[(int) (nanos.length * 0.99)] / 1e6, nanos[nanos.length - 1] / 1e6);
    }

    /**
     * The module call alone ({@code validate} on the reference request, no Java-side request building or
     * effects), for comparison with the spike's Chicory runtime-compiler figures.
     */
    @Test
    void moduleOnlyLatency() throws Exception {
        List<AmaruScenario> scenarios = AmaruScenarioLoader.fromEnvironment().loadAll();
        List<byte[]> requests = scenarios.stream()
                .map(s -> AmaruRequestEncoder.encode(ScenarioSupport.referenceRequest(s, AmaruRequest.Mode.FULL)))
                .toList();
        long[] nanos = new long[requests.size() * LATENCY_PASSES];
        Thread worker = Thread.ofPlatform().stackSize(AmaruEngineConfig.DEFAULT_WORKER_STACK_SIZE).start(() -> {
            try (WasmAmaruInstance instance = new WasmAmaruInstance(ScenarioSupport.MAX_MEMORY_PAGES)) {
                requests.forEach(instance::validate); // warm-up
                int n = 0;
                for (int pass = 0; pass < LATENCY_PASSES; pass++) {
                    for (byte[] request : requests) {
                        long start = System.nanoTime();
                        instance.validate(request);
                        nanos[n++] = System.nanoTime() - start;
                    }
                }
            }
        });
        worker.join();
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT, "Amaru module validate() alone over %d calls (warm, full mode): "
                        + "p50 %.3f ms, p90 %.3f ms, p99 %.3f ms%n", nanos.length, nanos[nanos.length / 2] / 1e6,
                nanos[(int) (nanos.length * 0.90)] / 1e6, nanos[(int) (nanos.length * 0.99)] / 1e6);
        assertThat(nanos[0]).isPositive();
    }

    /** @return null when the outcome is the expected one, otherwise what differs */
    static String mismatch(AmaruScenario scenario, TxValidationOutcome outcome) {
        if (scenario.env().protocolMajor() < 10) {
            return first(outcome) != null && first(outcome).rule() == LedgerRuleName.ENGINE
                    && first(outcome).constructor().equals(LedgerFailure.ERA_NOT_SUPPORTED)
                    ? null : "expected ENGINE.EraNotSupported below protocol version 10, got " + outcome;
        }
        return switch (scenario.expected()) {
            case Expected.Pass p -> outcome.isValid() ? null : "expected Pass, got " + outcome;
            case Expected.DecodingFailure d -> first(outcome) != null
                    && first(outcome).qualifiedName().equals("ENGINE." + AmaruTransactionValidator.DECODING_FAILURE)
                    ? null : "expected a decoding failure, got " + outcome;
            case Expected.Predicate p -> {
                LedgerFailure failure = first(outcome);
                if (failure == null) {
                    yield "expected " + p.qualifiedName() + ", got " + outcome;
                }
                boolean same = failure.rule() == p.rule() && failure.constructor().equals(p.constructor())
                        && failure.phase() == p.phase()
                        && (p.description() == null || failure.detail().startsWith(p.description() + ":"));
                yield same ? null : "expected " + p.qualifiedName() + " (" + p.corpusName() + ", " + p.phase()
                        + (p.description() != null ? ", " + p.description() : "") + "), got " + failure;
            }
        };
    }

    private static LedgerFailure first(TxValidationOutcome outcome) {
        return outcome instanceof TxValidationOutcome.Invalid invalid ? invalid.failures().getFirst() : null;
    }
}
