package com.bloxbean.cardano.yano.runtime.blockproducer;

import java.util.List;
import java.util.Optional;

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
     * Persist the latest nonce state together with the ChainState body cursor it
     * represents. The default implementation reuses the existing latest-state
     * key with the {@link NonceStateSnapshot} envelope.
     */
    default void storeLatestNonceSnapshot(NonceStateSnapshot snapshot) {
        storeEpochNonceState(snapshot.serialize());
    }

    /**
     * Return the latest cursor-bearing nonce snapshot if the latest-state key is
     * already in the snapshot format. Legacy raw {@link EpochNonceState} bytes
     * return {@link Optional#empty()} because they do not carry a cursor.
     */
    default Optional<NonceStateSnapshot> getLatestNonceSnapshot() {
        return NonceStateSnapshot.tryDeserialize(getEpochNonceState());
    }

    /**
     * Restore the latest nonce variables into {@code nonceState}. This helper is
     * intended for block-producer paths that already own the local chain cursor.
     * Relay/client sync should prefer startup repair, because legacy raw state
     * is not self-describing.
     */
    default boolean restoreLatestNonceState(EpochNonceState nonceState, boolean allowLegacyRawState) {
        byte[] persisted = getEpochNonceState();
        if (persisted == null) {
            return false;
        }
        Optional<NonceStateSnapshot> snapshot = NonceStateSnapshot.tryDeserialize(persisted);
        if (snapshot.isPresent()) {
            nonceState.restore(snapshot.get().nonceState());
            return true;
        }
        if (allowLegacyRawState) {
            nonceState.restore(persisted);
            return true;
        }
        return false;
    }

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

    /**
     * Persist a full nonce-state checkpoint for the epoch boundary. The existing
     * {@code epoch_nonce_by_epoch_<epoch>} value stores only the stable epoch
     * nonce; this checkpoint stores all evolving variables and a body cursor.
     */
    default void storeEpochNonceCheckpoint(int epoch, NonceStateSnapshot snapshot) {
    }

    /**
     * Return durable full-state epoch checkpoints whose cursor is at or before
     * {@code slot}. Results should be ordered newest first.
     */
    default List<NonceStateSnapshot> getEpochNonceCheckpointsAtOrBeforeSlot(long slot) {
        return List.of();
    }

    /**
     * Remove full-state epoch checkpoints after the supplied epoch. Used after
     * rollback repair so future checkpoints from the discarded chain are ignored.
     */
    default void pruneEpochNonceCheckpointsAfter(int epoch) {
    }
}
