package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Minimal peer-governor policy surface for selecting hot peers from a store.
 */
public final class PeerGovernor {
    private final PeerStore peerStore;

    public PeerGovernor(PeerStore peerStore) {
        this.peerStore = Objects.requireNonNull(peerStore, "peerStore");
    }

    public List<PeerStoreEntry> selectHotPeers(int targetHot) {
        if (targetHot <= 0) {
            return List.of();
        }
        return peerStore.all().stream()
                .sorted(Comparator
                        .comparing(PeerStoreEntry::trusted).reversed()
                        .thenComparing(Comparator.comparingInt(PeerStoreEntry::score).reversed())
                        .thenComparing(PeerStoreEntry::id))
                .limit(targetHot)
                .toList();
    }
}
