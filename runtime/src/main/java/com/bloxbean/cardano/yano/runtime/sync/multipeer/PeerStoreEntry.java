package com.bloxbean.cardano.yano.runtime.sync.multipeer;

/**
 * Peer-store entry used by rooted relay and discovery phases.
 */
public record PeerStoreEntry(
        String id,
        String host,
        int port,
        String source,
        boolean trusted,
        int score
) {
}
