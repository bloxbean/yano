package org.yanoproject.runtime.tx;

import org.yanoproject.ledger.rules.TransactionEvaluator;
import org.yanoproject.ledger.rules.TransactionValidator;

/**
 * Transaction services created by an edge adapter and installed by runtime assembly.
 */
public record TransactionServices(TransactionValidator validator,
                                  TransactionEvaluator scriptEvaluator) {
    public boolean hasServices() {
        return validator != null || scriptEvaluator != null;
    }
}
