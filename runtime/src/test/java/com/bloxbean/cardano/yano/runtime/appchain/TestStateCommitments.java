package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

import java.util.Optional;

/** Explicit authenticated-state identities used by runtime unit tests. */
final class TestStateCommitments {
    static final StateCommitmentIdentity MPF = StateCommitmentIdentity.explicit(
            StateCommitmentProfiles.MPF, new byte[32]);

    static AppStateWriter writer(MpfTrie trie) {
        return new AppStateWriter() {
            @Override public void put(byte[] key, byte[] value) { trie.put(key, value); }
            @Override public void delete(byte[] key) { trie.delete(key); }
            @Override public Optional<byte[]> get(byte[] key) {
                return Optional.ofNullable(trie.get(key));
            }
            @Override public byte[] stateRoot() { return trie.getRootHash(); }
        };
    }

    private TestStateCommitments() {
    }
}
