package com.bloxbean.cardano.yano.consensus.selection;

/**
 * Selects whether a candidate chain can be adopted.
 */
public interface ChainSelectionStrategy {
    ChainSelectionDecision evaluate(ChainSelectionContext context);
}
