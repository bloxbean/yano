package com.bloxbean.cardano.yano.runtime.chain;

/**
 * Detects and repairs local chain-state corruption.
 */
public interface ChainStateRecovery {
    boolean detectCorruption();

    void recoverFromCorruption();
}
