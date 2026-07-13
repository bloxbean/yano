package com.bloxbean.cardano.yano.api.appchain;

/**
 * Write access handed to {@link AppStateMachine#apply}. All mutations are
 * staged into the block's atomic commit (block + tip + state trie + root),
 * and become visible to {@link AppStateReader} only after the block commits.
 */
public interface AppStateWriter extends AppStateReader {

    void put(byte[] key, byte[] value);

    void delete(byte[] key);
}
