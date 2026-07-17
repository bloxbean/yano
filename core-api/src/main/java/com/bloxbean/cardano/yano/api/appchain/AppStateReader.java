package com.bloxbean.cardano.yano.api.appchain;

import java.util.Optional;

/**
 * Read access to the committed app state (the key/value content of the
 * state commitment trie as of the last finalized block).
 */
public interface AppStateReader {

    Optional<byte[]> get(byte[] key);

    /** Root of the state commitment after the last finalized block. */
    byte[] stateRoot();

    /**
     * Height represented by this committed view, when known. Legacy readers
     * default to genesis height; runtime readers expose the exact finalized
     * tip. State machines must use the candidate height supplied to
     * {@link AppStateMachine#validateForBlock} for admission decisions.
     */
    default long committedHeight() {
        return 0;
    }
}
