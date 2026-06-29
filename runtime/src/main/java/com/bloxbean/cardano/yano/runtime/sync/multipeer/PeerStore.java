package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import java.util.List;

/**
 * Persistent or ephemeral known-peer store.
 */
public interface PeerStore {
    void put(PeerStoreEntry peer);

    List<PeerStoreEntry> all();
}
