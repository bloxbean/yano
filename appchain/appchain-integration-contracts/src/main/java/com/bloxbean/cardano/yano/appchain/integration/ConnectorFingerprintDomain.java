package com.bloxbean.cardano.yano.appchain.integration;

/** Frozen domain separators for credential-free destination fingerprints. */
public enum ConnectorFingerprintDomain {
    /** Domain for a resolved Kafka cluster and physical topic. */
    KAFKA_DESTINATION("yano-kafka-destination-v1"),
    /** Domain for a resolved object-store bucket, key, and policy tuple. */
    OBJECT_DESTINATION("yano-object-destination-v1"),
    /** Domain for a resolved IPFS pinning target. */
    IPFS_TARGET("yano-ipfs-target-v1");

    private final String value;

    ConnectorFingerprintDomain(String value) {
        this.value = value;
    }

    /**
     * Returns the frozen ASCII domain separator.
     *
     * @return the fingerprint domain separator
     */
    public String value() {
        return value;
    }
}
