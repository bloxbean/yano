package com.bloxbean.cardano.yano.runtime.blockproducer;

/**
 * Supplies the protocol version for the epoch containing a block slot.
 */
@FunctionalInterface
public interface ProtocolVersionSupplier {

    ProtocolVersion getProtocolVersion(long slot);

    static ProtocolVersionSupplier fixed(long major, long minor) {
        ProtocolVersion version = new ProtocolVersion(major, minor);
        return slot -> version;
    }
}
