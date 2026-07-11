package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Read-only epoch nonce source for header validation stages.
 */
@FunctionalInterface
public interface HeaderValidationNonceProvider {
    byte[] epochNonceForSlot(long slot);

    static HeaderValidationNonceProvider none() {
        return slot -> null;
    }
}
