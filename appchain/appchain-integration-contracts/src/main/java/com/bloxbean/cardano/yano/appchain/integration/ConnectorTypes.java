package com.bloxbean.cardano.yano.appchain.integration;

/** Stable action identities for the ADR-013 v1 connector contracts. */
public final class ConnectorTypes {
    /** Effect type for acknowledged Kafka publication. */
    public static final String KAFKA_PUBLISH = "kafka.publish";
    /** Effect type for immutable object promotion. */
    public static final String OBJECT_PUT = "object.put";
    /** Effect type for canonical IPFS pinning. */
    public static final String IPFS_PIN = "ipfs.pin";

    private ConnectorTypes() {
    }
}
