package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

final class DuckLakeReadSession implements ArchiveReadSession {
    private final DuckLakeHistoryArchiveBackend backend;
    private final long generation;
    private final DuckDbLease lease;
    private final Runnable releaseGate;
    private final AtomicBoolean closed = new AtomicBoolean();

    DuckLakeReadSession(DuckLakeHistoryArchiveBackend backend, long generation, DuckDbLease lease,
                        Runnable releaseGate) {
        this.backend = backend;
        this.generation = generation;
        this.lease = lease;
        this.releaseGate = releaseGate;
    }

    @Override
    public long generation() {
        return generation;
    }

    Connection connection() {
        if (closed.get()) throw new IllegalStateException("DuckLake read session is closed");
        return lease.connection();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            DuckLakeSql.detach(lease.connection());
        } catch (SQLException ignored) {
        } finally {
            lease.close();
            backend.releaseSnapshot(generation);
            releaseGate.run();
        }
    }
}
