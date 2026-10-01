package org.yanoproject.ledger.amaru;

import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

/**
 * {@code yano.validation.engine=amaru-scalus} (ADR-057 §2): Amaru judges phase one ({@code mode = phase_one}) and the
 * node's Scalus {@code ScriptPhaseEvaluator} runs the Plutus scripts. {@code amaru} ({@link AmaruEngineFactory}) runs
 * both phases on Amaru. Same {@code yano.validation.amaru.*} settings.
 *
 * <p>The node must provide the Scalus evaluator ({@code EngineContext#scriptPhaseEvaluator()}), or startup stops.</p>
 */
public final class AmaruScalusEngineFactory implements LedgerValidationEngineFactory {

    @Override
    public String name() {
        return LedgerValidationEngines.AMARU_SCALUS;
    }

    @Override
    public LedgerValidationEngine create(EngineContext context) {
        ScriptPhaseEvaluator scalus = context.scriptPhaseEvaluator();
        if (scalus == null) {
            throw new IllegalStateException("Validation engine '" + name() + "' needs the Scalus phase-2 evaluator "
                    + "(scalus-bridge), which is not available; use yano.validation.engine=amaru");
        }
        return AmaruEngineFactory.create(name(), context, scalus);
    }
}
