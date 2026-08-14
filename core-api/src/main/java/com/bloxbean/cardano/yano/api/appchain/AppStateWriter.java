package com.bloxbean.cardano.yano.api.appchain;

/**
 * Write access handed to {@link AppStateMachine#apply}. All mutations are
 * staged into the block's atomic commit (block + tip + state trie + root),
 * and become visible to {@link AppStateReader} only after the block commits.
 */
public interface AppStateWriter extends AppStateReader {

    /**
     * Consensus-safe cross-cutting capabilities enabled for this writer's
     * namespace. The immutable empty registry preserves legacy transitions
     * byte-for-byte when no capability is configured.
     */
    default AppStateCapabilities capabilities() {
        return AppStateCapabilities.empty();
    }

    void put(byte[] key, byte[] value);

    void delete(byte[] key);
}
