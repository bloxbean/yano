package org.yanoproject.runtime.tx;

import org.yanoproject.ledger.rules.TransactionEvaluator;
import org.yanoproject.ledger.rules.TransactionValidator;
import org.yanoproject.runtime.validation.ValidationEngines;

/**
 * Transaction services created by an edge adapter and installed by runtime assembly.
 *
 * @param validator         the legacy Scalus validator (admission and block selection under {@code engine: scalus})
 * @param scriptEvaluator   the ExUnits evaluator for {@code /utils/txs/evaluate}
 * @param validationEngines the engine-API admission engines (ADR-056 §7), or {@code null} for the legacy path
 */
public record TransactionServices(TransactionValidator validator,
                                  TransactionEvaluator scriptEvaluator,
                                  ValidationEngines validationEngines) {

    public TransactionServices(TransactionValidator validator, TransactionEvaluator scriptEvaluator) {
        this(validator, scriptEvaluator, null);
    }

    public boolean hasServices() {
        return validator != null || scriptEvaluator != null;
    }
}
