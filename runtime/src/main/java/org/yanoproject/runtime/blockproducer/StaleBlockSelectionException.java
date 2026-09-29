package org.yanoproject.runtime.blockproducer;

/**
 * The canonical generation a block's transaction selection was validated against changed before the block was
 * stored (ADR-056 §6, "Block production"): the forged block is discarded, never stored, and the next production
 * attempt selects again against the new canonical state.
 */
public final class StaleBlockSelectionException extends IllegalStateException {

    public StaleBlockSelectionException(long slot) {
        super("The canonical state changed after the transactions for slot " + slot
                + " were selected; the forged block is discarded and the selection redone");
    }
}
