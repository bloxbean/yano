package org.yanoproject.ledger.amaru;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.TxValidationRequest.Rule;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 step 1d / ADR-057 deviation 9: the scenario gate for {@code engine: amaru-scalus}. Amaru judges phase
 * one ({@code mode = phase_one}); the real Scalus {@code ScriptPhaseEvaluator} runs the scripts and reports the
 * phase-1 checks Amaru makes while preparing script contexts ({@code MalformedScriptWitnesses},
 * {@code CollectErrors}). Every scenario must get the same verdict, rule and Haskell constructor as
 * {@code engine: amaru} ({@link AmaruScenarioGateTest#mismatch}).
 */
class AmaruScalusModeGateTest {

    private static final Map<String, AmaruTransactionValidator> ENGINES = new ConcurrentHashMap<>();

    @Test
    void everyScenarioMatchesWithTheScalusPhaseTwoEvaluator() {
        List<AmaruScenario> scenarios = AmaruScenarioLoader.fromEnvironment().loadAll();
        assertThat(scenarios).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);
        ScalusScriptPhaseEvaluator evaluator = new ScalusScriptPhaseEvaluator();

        List<String> mismatches = new ArrayList<>();
        int matched = 0;
        int refusedBelowConway = 0;
        for (AmaruScenario scenario : scenarios) {
            AmaruNetworkParameters network = ScenarioSupport.network(scenario);
            AmaruLedgerConstants constants = ScenarioSupport.constants(scenario);
            AmaruTransactionValidator engine = ENGINES.computeIfAbsent(network + "|" + constants,
                    key -> new AmaruTransactionValidator(LedgerValidationEngines.AMARU_SCALUS,
                            AmaruEngineConfig.defaults(2), network, evaluator, constants, ScenarioSupport.wasm()));
            TxValidationOutcome outcome = ScenarioSupport.validate(engine, scenario, Rule.LEDGER, Origin.SYNC);
            String problem = AmaruScenarioGateTest.mismatch(scenario, outcome);
            if (problem == null) {
                matched++;
                if (scenario.env().protocolMajor() < 10) {
                    refusedBelowConway++;
                }
            } else {
                mismatches.add(scenario.name() + ": " + problem);
            }
        }
        System.out.printf(Locale.ROOT, "Amaru scenarios under amaru-scalus: %d of %d matched "
                + "(%d refused below protocol version 10 by invariant 6)%n", matched, scenarios.size(),
                refusedBelowConway);
        mismatches.forEach(m -> System.out.println("  MISMATCH " + m));
        assertThat(mismatches).as("scenario mismatches under amaru-scalus").isEmpty();
    }
}
