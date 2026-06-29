package com.bloxbean.cardano.yano.runtime.sync.multipeer;

/**
 * Selects whether a candidate chain can be adopted.
 */
public interface ChainSelectionStrategy {
    ChainSelectionDecision evaluate(ChainSelectionContext context);
}
