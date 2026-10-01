package org.yanoproject.scalusbridge;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * ADR-056 Phase 7c: chain-valid preprod and preview transactions that shadow sync reported
 * ({@link PublicNetworkTransactions#PHASE2_CASES}), replayed through the java engine with the Scalus phase-2
 * evaluator. Each must validate, as it did on chain.
 */
class PublicNetworkPhase2Test {

    private final JavaLedgerValidationEngine engine = new JavaLedgerValidationEngine(new ScalusScriptPhaseEvaluator());

    @TestFactory
    Stream<DynamicTest> chainValidTransactionsValidate() {
        return PublicNetworkTransactions.PHASE2_CASES.stream().map(c -> dynamicTest(c.toString(), () -> {
            TxValidationOutcome outcome = engine.validate(c.bundle().replayRequest());
            assertThat(outcome).as(c + ": " + outcome).isInstanceOf(TxValidationOutcome.Valid.class);
        }));
    }
}
