package com.bloxbean.cardano.yano.archive.api.projection;

/**
 * The identity of one deterministic projection job.
 *
 * <p>Shared by the encoded {@link ProjectionBatch} the outbox produces and the
 * materialised {@link ProjectionRowBatch} a sink consumes, so receipt identity and
 * idempotency are defined exactly once rather than reimplemented on each side.
 */
public interface ProjectionJob {
    ProjectionIdentity identity();

    long firstBlock();

    long lastBlock();

    long blockCount();

    String firstEnvelopeId();

    String lastEnvelopeId();

    String orderedDigest();
}
