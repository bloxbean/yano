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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-056 Phase 3a gate: the Java engine ({@code java-engine}) against the Amaru scenarios it can already
 * judge.
 *
 * <p><b>Gate set.</b> Every scenario whose expected constructor is in the {@code UTXO} or {@code UTXOS} family,
 * and every scenario expected to pass. The engine runs {@code UTXOW} (script preparation only), {@code UTXO} and
 * {@code UTXOS}; the other families record nothing yet, so a valid transaction passes exactly when these rules
 * accept it, which makes every {@code Pass} scenario a false-rejection check of the {@code UTXO} and
 * {@code UTXOS} rules (value conservation with deposits, refunds, proposals, donations and withdrawals; fees with
 * reference scripts; collateral; phase 2). Scenarios of the other families are left to Phases 3b–5.</p>
 *
 * <p>Scenario 00088 (Amaru: {@code OutsideForecast}) is expected as {@code UTXOS.CollectErrors}: Amaru's Haskell
 * checker names {@code CollectErrors [BadTranslation TimeTranslationPastHorizon]} so, and Haskell's own
 * {@code OutsideForecast} cannot occur at the pin ({@code AmaruCorpusNames}).</p>
 *
 * <p><b>Pass criterion.</b> The verdict and the constructor match ({@link CaseResult#constructorMatch()}: the
 * first failure is the expected constructor, or any constructor of Haskell's list for faults Haskell reports
 * with several), or the scenario is a recorded Haskell-vs-Amaru divergence and the engine reports Haskell's
 * constructor.</p>
 */
class JavaEnginePhase3aGateTest {

    /** Families of the expected constructor that the gate covers (plus {@code PASS}). */
    private static final Set<String> GATE_FAMILIES = Set.of("PASS", "UTXO", "UTXOS");

    /**
     * Recorded divergences: Amaru's corpus expects one constructor, Haskell (cardano-ledger {@code f649f975})
     * reports another. Haskell wins (ADR-056 invariant 1); see ADR-056 "Phase 3a results".
     */
    static final Map<String, Divergence> DIVERGENCES = Map.of(
            "00280-fail-output-to-a-byron-address-with-oversized-attributes",
            new Divergence("UTXO.OutputBootAddrAttrsTooBig", "the output's value is Coin 2000000 (5 bytes, far below "
                    + "maxValSize), so validateOutputTooBigUTxO (Alonzo/Rules/Utxo.hs:412-434) holds; its Byron "
                    + "address carries 99 bytes of attributes > 64 (Shelley/Rules/Utxo.hs:544-561)"));

    /** @param haskell Haskell's first failure ({@code RULE.Constructor}) */
    record Divergence(String haskell, String reason) {
    }

    @Test
    void utxoAndUtxosScenariosMatchHaskell() {
        List<ConformanceCase> all = ScenarioCases.cases().orElse(List.of());
        Assumptions.assumeFalse(all.isEmpty(), "the Amaru scenarios are not configured (-PamaruScenariosDir)");
        assertThat(all).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);

        JavaViewEngine engine = new JavaViewEngine();
        List<String> misses = new ArrayList<>();
        int gate = 0;
        int matched = 0;
        int divergent = 0;
        int passScenarios = 0;
        for (ConformanceCase testCase : all) {
            if (!GATE_FAMILIES.contains(testCase.family())) {
                continue;
            }
            gate++;
            if (testCase.family().equals("PASS")) {
                passScenarios++;
            }
            CaseResult result = ConformanceRunner.run(engine, testCase);
            Divergence divergence = DIVERGENCES.get(testCase.id());
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
        System.out.printf("Phase 3a gate: %d scenarios (%d pass, %d UTXO/UTXOS), %d match Amaru and Haskell, "
                + "%d match Haskell where Amaru diverges%n", gate, passScenarios, gate - passScenarios, matched,
                divergent);
        assertThat(misses).as("Phase 3a gate misses").isEmpty();
        assertThat(divergent).isEqualTo(DIVERGENCES.size());
    }
}
