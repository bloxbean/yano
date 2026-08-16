package com.bloxbean.cardano.yano.archive.core.source;

import java.time.Instant;
import java.util.UUID;

/** Durable source-retention claim; close releases only after a verified commit. */
public interface ArchiveSourceLease extends AutoCloseable {
    UUID leaseId();

    Instant expiresAt();

    ArchiveSourceLease renew(Instant newExpiry);

    @Override
    void close();
}
