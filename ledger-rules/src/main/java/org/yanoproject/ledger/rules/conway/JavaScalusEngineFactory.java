package org.yanoproject.ledger.rules.conway;

import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;
import org.yanoproject.ledger.rules.LedgerValidationEngines;

/**
 * {@code java-scalus} (ADR-056 Phase 7c): the {@link JavaLedgerValidationEngine} with the Scalus phase-2 evaluator
 * instead of julc ({@code java-julc}, {@link JavaJulcEngineFactory}), for users who need it. One node can run both, for
 * example {@code yano.validation.shadow-sync-engines: java-julc,java-scalus}: every transaction then gets both
 * verdicts, and counters, health, report lines and replay bundles carry the engine id.
 *
 * <p>Without the node's Scalus evaluator ({@code EngineContext#scriptPhaseEvaluator()}), the engine fails closed on
 * every transaction that needs phase 2.</p>
 */
public final class JavaScalusEngineFactory implements LedgerValidationEngineFactory {

    @Override
    public String name() {
        return LedgerValidationEngines.JAVA_SCALUS;
    }

    @Override
    public LedgerValidationEngine create(EngineContext context) {
        return new JavaLedgerValidationEngine(name(), context.scriptPhaseEvaluator());
    }
}
