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
 * The ADR-056 Phase 4 gate: the Java engine ({@code java-engine}) against every Amaru scenario of the families
 * implemented so far — {@code CERTS}, {@code DELEG}, {@code POOL}, {@code GOVCERT} (Phase 4) and {@code UTXOW},
 * {@code UTXO}, {@code UTXOS} (Phase 3) — and every scenario expected to pass.
 *
 * <p>With {@code CERTS} in the transition, every {@code Pass} scenario with certificates or withdrawals is now also a
 * false-rejection check of the certificate rules and of the intra-transaction state (register then delegate or
 * deregister, a DRep or pool registered and then used, withdrawals drained before a deregistration, committee
 * authorisations of proposed members). {@code GOV} and the {@code LEDGER} pre-checks are still Phase 5.</p>
 *
 * <p><b>Pass criterion</b> as in {@link JavaEnginePhase3GateTest}: verdict and constructor (the first failure, or any
 * constructor of Haskell's list where Haskell reports several), or a recorded Haskell-vs-Amaru divergence with the
 * engine reporting Haskell's constructor.</p>
 */
class JavaEnginePhase4GateTest {

    /** Families of the expected constructor that the gate covers (plus {@code PASS}). */
    private static final Set<String> GATE_FAMILIES = Set.of("PASS", "UTXO", "UTXOW", "UTXOS", "CERTS", "DELEG",
            "POOL", "GOVCERT");

    /** Families Phase 4 adds. */
    private static final Set<String> PHASE_4_FAMILIES = Set.of("CERTS", "DELEG", "POOL", "GOVCERT");

    /**
     * Recorded divergences: Amaru's corpus expects one constructor, Haskell (cardano-ledger {@code f649f975})
     * reports another. Haskell wins (ADR-056 invariant 1).
     */
    static final Map<String, JavaEnginePhase3GateTest.Divergence> DIVERGENCES = JavaEnginePhase3GateTest.DIVERGENCES;

    @Test
    void certificateFamiliesAndEveryEarlierFamilyMatchHaskell() {
        List<ConformanceCase> all = ScenarioCases.cases().orElse(List.of());
        Assumptions.assumeFalse(all.isEmpty(), "the Amaru scenarios are not configured (-PamaruScenariosDir)");
        assertThat(all).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);

        JavaViewEngine engine = new JavaViewEngine();
        List<String> misses = new ArrayList<>();
        Map<String, Integer> perFamily = new TreeMap<>();
        int gate = 0;
        int matched = 0;
        int divergent = 0;
        for (ConformanceCase testCase : all) {
            if (!GATE_FAMILIES.contains(testCase.family())) {
                continue;
            }
            gate++;
            perFamily.merge(testCase.family(), 1, Integer::sum);
            CaseResult result = ConformanceRunner.run(engine, testCase);
            JavaEnginePhase3GateTest.Divergence divergence = DIVERGENCES.get(testCase.id());
            if (divergence != null) {
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
        long certificateScenarios = perFamily.entrySet().stream().filter(e -> PHASE_4_FAMILIES.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        System.out.printf("Phase 4 gate: %d scenarios %s (%d CERTS/DELEG/POOL/GOVCERT), %d match Amaru and Haskell, "
                + "%d match Haskell where Amaru diverges%n", gate, perFamily, certificateScenarios, matched, divergent);
        assertThat(misses).as("Phase 4 gate misses").isEmpty();
        assertThat(divergent).isEqualTo(DIVERGENCES.size());
    }
}
