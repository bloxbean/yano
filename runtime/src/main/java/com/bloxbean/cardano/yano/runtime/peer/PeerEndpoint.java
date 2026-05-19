package com.bloxbean.cardano.yano.runtime.peer;

import java.util.Objects;

/**
 * Configured upstream endpoint for one peer session.
 */
public record PeerEndpoint(String host, int port, long protocolMagic) {
    public PeerEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    public String displayName() {
        return host + ":" + port;
    }
}
