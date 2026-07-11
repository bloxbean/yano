package com.bloxbean.cardano.yano.p2p.governor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Peer-store entry used by rooted relay and discovery phases.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PeerStoreEntry(
        String id,
        String host,
        int port,
        String source,
        boolean trusted
) {
    public PeerStoreEntry(String id, String host, int port, String source, boolean trusted, int ignoredScore) {
        this(id, host, port, source, trusted);
    }
}
