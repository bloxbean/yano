package com.bloxbean.cardano.yano.api.archive;

/**
 * Stages projection records into a contributor's in-flight write batch.
 *
 * <p>Deliberately opaque. The runtime owns the batch and knows nothing about projection
 * key or value encoding; the archive owns the encoding and knows nothing about how the
 * batch is committed. That keeps ADR-039's atomicity guarantee — a section is durable
 * exactly when the state it was derived from is durable — without either side depending
 * on the other's internals, and without {@code core-api} taking a RocksDB dependency.
 */
@FunctionalInterface
public interface ProjectionStagingWriter {
    /**
     * @param columnFamily one of {@link ProjectionCfNames}
     * @param key   fully encoded record key
     * @param value fully encoded record value
     */
    void put(String columnFamily, byte[] key, byte[] value);
}
