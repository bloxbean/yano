package com.bloxbean.cardano.yano.runtime.blockproducer;

/**
 * Protocol version stamped into produced block headers.
 */
public record ProtocolVersion(long major, long minor) {
    public ProtocolVersion {
        if (major <= 0) {
            throw new IllegalArgumentException("protocol major version must be positive");
        }
        if (minor < 0) {
            throw new IllegalArgumentException("protocol minor version must be non-negative");
        }
    }
}
