package org.yanoproject.ledger.conformance.gate;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.conformance.runner.CaseResult;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceRunner;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-056 Phase 5 gate: the Java engine ({@code java-engine}, every rule family) against <em>all</em> 276 Amaru
 * scenarios. Each must match (verdict and constructor: the first failure, any constructor of Haskell's list where
 * Haskell reports several, or a constructor Amaru's checker reports under the same corpus name), or be a recorded
 * divergence with the engine reporting what Haskell reports.
 *
 * <p>Recorded divergences: {@link JavaEnginePhase3GateTest#DIVERGENCES} (Haskell-vs-Amaru, the engine reports
 * Haskell's constructor).</p>
 *
 * <p>Since ADR-056 Phase 5b the engine validates protocol version 9 (the bootstrap phase) too, so the corpus's protocol
 * version 9 scenarios ({@link #BOOTSTRAP_SCENARIOS}) must match their expected (Haskell-cross-checked) predicate, although
 * the Amaru reference engine refuses them ({@code ENGINE.EraNotSupported}, its own minimum, invariant 6).</p>
 */
class JavaEnginePhase5GateTest {

    /** The corpus's protocol version 9 scenarios: validated by the Java engine since Phase 5b, refused by Amaru. */
    static final Set<String> BOOTSTRAP_SCENARIOS = Set.of("00203-fail-gov-hardfork-initiation-chains-two-majors");

    @Test
    void everyScenarioMatchesHaskell() {
        List<ConformanceCase> all = ScenarioCases.cases().orElse(List.of());
        Assumptions.assumeFalse(all.isEmpty(), "the Amaru scenarios are not configured (-PamaruScenariosDir)");
        assertThat(all).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);

        JavaViewEngine engine = new JavaViewEngine();
        List<String> misses = new ArrayList<>();
        Map<String, Integer> perFamily = new TreeMap<>();
        int matched = 0;
        int divergent = 0;
        int bootstrap = 0;
        for (ConformanceCase testCase : all) {
            perFamily.merge(testCase.family(), 1, Integer::sum);
            CaseResult result = ConformanceRunner.run(engine, testCase);
            JavaEnginePhase3GateTest.Divergence divergence = JavaEnginePhase3GateTest.DIVERGENCES.get(testCase.id());
            if (testCase.env().protocolMajor() < 10) {
                if (!BOOTSTRAP_SCENARIOS.contains(testCase.id())) {
                    misses.add(testCase.id() + ": an unrecorded protocol version " + testCase.env().protocolMajor()
                            + " scenario");
                } else if (result.constructorMatch()) {
                    bootstrap++;
                } else {
                    misses.add(testCase.id() + " (protocol version 9): expected " + testCase.expectedLabel()
                            + ", got " + result.observation().label() + " " + result.observation().failures());
                }
            } else if (divergence != null) {
                if (!result.observation().valid()
                        && result.observation().first().qualifiedName().equals(divergence.haskell())) {
                    divergent++;
                } else {
                    misses.add(testCase.id() + ": Haskell reports " + divergence.haskell() + ", got "
                            + result.observation().label());
                }
            } else if (result.constructorMatch()) {
                matched++;
            } else {
                misses.add(testCase.id() + ": expected " + testCase.expectedLabel() + ", got "
                        + result.observation().label() + " " + result.observation().failures());
            }
        }
        System.out.printf("Phase 5 gate: %d scenarios %s, %d match Amaru and Haskell, %d match Haskell where Amaru "
                + "diverges, %d match Haskell at protocol version 9 (Amaru refuses them)%n", all.size(), perFamily,
                matched, divergent, bootstrap);
        assertThat(misses).as("Phase 5 gate misses").isEmpty();
        assertThat(divergent).isEqualTo(JavaEnginePhase3GateTest.DIVERGENCES.size());
        assertThat(bootstrap).isEqualTo(BOOTSTRAP_SCENARIOS.size());
        assertThat(matched + divergent + bootstrap).isEqualTo(AmaruScenarioLoader.EXPECTED_SCENARIOS);
    }
}
