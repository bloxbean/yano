package org.yanoproject.ledger.conformance.diversity.ledgerrules;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.runner.CaseResult;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.conformance.runner.Observation.Failure;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerRulesResultTest {

    @Test
    void acceptedIsPass() {
        assertThat(result(new Expected.Pass(), Observation.accepted())).isEqualTo("pass");
    }

    @Test
    void expectedPredicateCountsInAnyPosition() {
        Expected expected = predicate("StakeKeyNotRegistered", LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG");
        Observation observation = Observation.rejected(List.of(failure("UTXO", "ValueNotConservedUTxO"),
                failure("DELEG", "StakeKeyNotRegisteredDELEG")));

        assertThat(result(expected, observation)).isEqualTo("StakeKeyNotRegistered");
    }

    @Test
    void otherwiseTheFirstFailureUnderItsCorpusName() {
        assertThat(result(new Expected.Pass(), Observation.rejected(List.of(failure("UTXOS", "CollectErrors")))))
                .isEqualTo("OutsideForecast");
        assertThat(result(new Expected.Pass(),
                Observation.rejected(List.of(failure("DELEG", "StakeKeyNotRegisteredDELEG")))))
                .isEqualTo("StakeCredentialInvalidPoolDelegation");
        assertThat(result(new Expected.Pass(),
                Observation.rejected(List.of(failure("GOV", "UnelectedCommitteeVoters")))))
                .isEqualTo("VotersDoNotExist");
    }

    @Test
    void aConstructorWithoutCorpusNameIsReportedBare() {
        Expected expected = predicate("OutputTooBigUTxO", LedgerRuleName.UTXO, "OutputTooBigUTxO");
        Observation observation = Observation.rejected(List.of(failure("UTXO", "OutputBootAddrAttrsTooBig")));

        assertThat(result(expected, observation)).isEqualTo("OutputBootAddrAttrsTooBig");
    }

    @Test
    void decodingFailure() {
        Observation observation = Observation.rejected(List.of(failure("ENGINE", "DecodingFailure")));

        assertThat(result(new Expected.DecodingFailure(), observation)).isEqualTo("DecodingFailure");
        assertThat(result(new Expected.Pass(), observation)).isEqualTo("DecodingFailure");
    }

    private static String result(Expected expected, Observation observation) {
        ConformanceCase testCase = new ConformanceCase("00001-case", "case", ConformanceCase.Kind.SCENARIO,
                new byte[] {(byte) 0x80}, MutationWorld.view(10), MutationWorld.env(10), MutationWorld.network(),
                AmaruScenario.LedgerConstants.NONE, expected, List.of());
        return LedgerRulesResult.of(new CaseResult("java-julc", testCase, observation, 0));
    }

    private static Expected predicate(String corpusName, LedgerRuleName rule, String constructor) {
        return new Expected.Predicate(corpusName, rule, constructor, LedgerFailure.Phase.PHASE_1, null);
    }

    private static Failure failure(String rule, String constructor) {
        return new Failure(rule, constructor, "");
    }
}
