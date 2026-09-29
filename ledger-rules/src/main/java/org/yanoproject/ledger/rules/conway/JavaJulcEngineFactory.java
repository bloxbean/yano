package org.yanoproject.ledger.rules.conway;

import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

/**
 * {@code yano.validation.engine=java-julc} (ADR-056 §7 and Phase 7c): the {@link JavaLedgerValidationEngine} with the
 * julc phase-2 evaluator, the Java engine wherever one is defaulted. {@code java-scalus}
 * ({@link JavaScalusEngineFactory}) is the same rules with Scalus phase 2.
 *
 * <p>Both are opt-in until Phase 8 ({@value JavaLedgerValidationEngine#EXPERIMENTAL_KEY}). The node must provide the
 * julc evaluator ({@code EngineContext#julcScriptPhaseEvaluator()}), or startup stops.</p>
 */
public final class JavaJulcEngineFactory implements LedgerValidationEngineFactory {

    @Override
    public String name() {
        return LedgerValidationEngines.JAVA_JULC;
    }

    @Override
    public LedgerValidationEngine create(EngineContext context) {
        JavaLedgerValidationEngine.requireExperimental(context, name());
        ScriptPhaseEvaluator julc = context.julcScriptPhaseEvaluator();
        if (julc == null) {
            throw new IllegalStateException("Validation engine '" + name() + "' needs the julc phase-2 evaluator, "
                    + "which this node does not provide (script-evaluators is not on the classpath)");
        }
        return new JavaLedgerValidationEngine(name(), julc);
    }
}
