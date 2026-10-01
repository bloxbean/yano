package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.scalusbridge.ScalusLedgerValidationEngine;

/**
 * The {@code scalus} engine of the engine API ({@link ScalusLedgerValidationEngine}, ADR-056 step 1d) over the
 * case's view: rule {@code LEDGER}, origin {@code SYNC}.
 */
public final class ScalusViewEngine implements ConformanceEngine {

    @Override
    public String name() {
        return "scalus-engine";
    }

    @Override
    public String description() {
        return "ScalusLedgerValidationEngine over the LedgerView (engine API, step 1d)";
    }

    @Override
    public Observation validate(ConformanceCase testCase) {
        ScalusLedgerValidationEngine engine = new ScalusLedgerValidationEngine(CaseTiming.slotConfig(testCase));
        return Observation.of(engine.validate(new TxValidationRequest(testCase.txCbor(), testCase.view(),
                testCase.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null)));
    }
}
