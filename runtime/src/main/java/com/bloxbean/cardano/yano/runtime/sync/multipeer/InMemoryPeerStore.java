package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral peer store for early rooted-relay/discovery wiring and tests.
 */
public final class InMemoryPeerStore implements PeerStore {
    private final Map<String, PeerStoreEntry> peers = new ConcurrentHashMap<>();

    @Override
    public void put(PeerStoreEntry peer) {
        peers.put(peer.id(), peer);
    }

    @Override
    public List<PeerStoreEntry> all() {
        return peers.values().stream()
                .sorted(Comparator.comparing(PeerStoreEntry::id))
                .toList();
    }
}
