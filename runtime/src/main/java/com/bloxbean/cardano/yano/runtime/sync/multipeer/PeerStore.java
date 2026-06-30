package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import java.util.List;

/**
 * Persistent or ephemeral known-peer store.
 */
public interface PeerStore {
    void put(PeerStoreEntry peer);

    default void replaceAll(List<PeerStoreEntry> peers) {
        if (peers != null) {
            peers.forEach(this::put);
        }
    }

    List<PeerStoreEntry> all();
}
