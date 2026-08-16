package com.bloxbean.cardano.yano.archive.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One pinned read session paired with its common finalized coverage boundary. */
public final class ArchiveConsistentRead implements AutoCloseable {
    private final ArchiveReadSession session;
    private final ArchiveConsistencyPoint point;
    private final AutoCloseable outerLease;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ArchiveConsistentRead(ArchiveReadSession session, ArchiveConsistencyPoint point) {
        this(session, point, () -> { });
    }

    public ArchiveConsistentRead(ArchiveReadSession session, ArchiveConsistencyPoint point,
                                 AutoCloseable outerLease) {
        this.session = Objects.requireNonNull(session, "session");
        this.point = Objects.requireNonNull(point, "point");
        this.outerLease = Objects.requireNonNull(outerLease, "outerLease");
        if (session.generation() != point.generation()) {
            throw new IllegalArgumentException("read point generation does not match its session");
        }
    }

    public ArchiveReadSession session() { return session; }

    public ArchiveConsistencyPoint point() { return point; }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            session.close();
        } finally {
            try {
                outerLease.close();
            } catch (Exception e) {
                throw new ArchiveStoreException("failed to release archive read lease", e);
            }
        }
    }
}
