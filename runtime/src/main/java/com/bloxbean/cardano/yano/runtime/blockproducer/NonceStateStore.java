package com.bloxbean.cardano.yano.runtime.blockproducer;

/**
 * Persistence interface for epoch nonce state.
 * Allows {@link EpochNonceState} to be serialized/restored across restarts.
 */
public interface NonceStateStore {

    /**
     * Persist the serialized epoch nonce state.
     *
     * @param serialized compact binary representation from {@link EpochNonceState#serialize()}
     */
    void storeEpochNonceState(byte[] serialized);

    /**
     * Retrieve the persisted epoch nonce state.
     *
     * @return serialized bytes, or null if no state has been stored
     */
    byte[] getEpochNonceState();

    /**
     * Persist the ledger epoch nonce for a finalized epoch.
     *
     * @param epoch epoch number
     * @param nonce 32-byte epoch nonce
     */
    default void storeEpochNonce(int epoch, byte[] nonce) {
    }

    /**
     * Retrieve the ledger epoch nonce for a finalized epoch.
     *
     * @param epoch epoch number
     * @return 32-byte epoch nonce, or null if not stored
     */
    default byte[] getEpochNonce(int epoch) {
        return null;
    }

    /**
     * Remove epoch nonce entries after the supplied epoch. Used when nonce state
     * rolls back across an epoch boundary.
     *
     * @param epoch highest epoch to retain
     */
    default void pruneEpochNoncesAfter(int epoch) {
    }
}
