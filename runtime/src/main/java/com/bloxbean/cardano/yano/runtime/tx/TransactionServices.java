package com.bloxbean.cardano.yano.runtime.tx;

import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;

/**
 * Transaction services created by an edge adapter and installed by runtime assembly.
 */
public record TransactionServices(TransactionValidator validator,
                                  TransactionEvaluator scriptEvaluator) {
    public boolean hasServices() {
        return validator != null || scriptEvaluator != null;
    }
}
