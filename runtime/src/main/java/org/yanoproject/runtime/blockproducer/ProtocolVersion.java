package org.yanoproject.runtime.blockproducer;

/**
 * A protocol version. Produced block headers carry its major version (see
 * {@code DevnetBlockBuilder.resolveProtocolVersion}).
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
