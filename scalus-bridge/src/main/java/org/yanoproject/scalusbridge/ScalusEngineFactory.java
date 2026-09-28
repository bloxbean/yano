package org.yanoproject.scalusbridge;

import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;

/**
 * {@code yano.validation.engine=scalus} (and {@code shadow-engines: [scalus]}): the
 * {@link ScalusLedgerValidationEngine} over the request's ledger view (ADR-056 §7).
 */
public final class ScalusEngineFactory implements LedgerValidationEngineFactory {

    @Override
    public String name() {
        return ScalusLedgerValidationEngine.NAME;
    }

    @Override
    public LedgerValidationEngine create(EngineContext context) {
        return new ScalusLedgerValidationEngine(context.slotConfig());
    }
}
