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
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-056 Phase 5 gate: the Java engine ({@code java-engine}, every rule family) against <em>all</em> 276 Amaru
 * scenarios. Each must match (verdict and constructor: the first failure, any constructor of Haskell's list where
 * Haskell reports several, or a constructor Amaru's checker reports under the same corpus name), or be a recorded
 * divergence with the engine reporting what Haskell reports.
 *
 * <p>Recorded divergences:</p>
 * <ul>
 *   <li>{@link JavaEnginePhase3GateTest#DIVERGENCES} (Haskell-vs-Amaru, the engine reports Haskell's constructor);</li>
 *   <li>{@link #OUT_OF_SCOPE}: scenarios at protocol version 9, which the engine refuses by design
 *       ({@code ENGINE.EraNotSupported}, ADR-056 invariant 7), as the Amaru reference engine does.</li>
 * </ul>
 */
class JavaEnginePhase5GateTest {

    /** Protocol version 9 scenarios: refused by design (invariant 7). */
    static final Map<String, String> OUT_OF_SCOPE = Map.of(
            "00203-fail-gov-hardfork-initiation-chains-two-majors",
            "protocol version 9.0: the bootstrap phase is out of scope (ADR-056 invariant 7); Amaru refuses it too");

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
        int outOfScope = 0;
        for (ConformanceCase testCase : all) {
            perFamily.merge(testCase.family(), 1, Integer::sum);
            CaseResult result = ConformanceRunner.run(engine, testCase);
            JavaEnginePhase3GateTest.Divergence divergence = JavaEnginePhase3GateTest.DIVERGENCES.get(testCase.id());
            if (OUT_OF_SCOPE.containsKey(testCase.id())) {
                if (!result.observation().valid()
                        && result.observation().first().qualifiedName().equals("ENGINE.EraNotSupported")) {
                    outOfScope++;
                } else {
                    misses.add(testCase.id() + ": expected ENGINE.EraNotSupported, got " + result.observation().label());
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
                + "diverges, %d out of scope (protocol version 9)%n", all.size(), perFamily, matched, divergent,
                outOfScope);
        assertThat(misses).as("Phase 5 gate misses").isEmpty();
        assertThat(divergent).isEqualTo(JavaEnginePhase3GateTest.DIVERGENCES.size());
        assertThat(outOfScope).isEqualTo(OUT_OF_SCOPE.size());
        assertThat(matched + divergent + outOfScope).isEqualTo(AmaruScenarioLoader.EXPECTED_SCENARIOS);
    }
}
