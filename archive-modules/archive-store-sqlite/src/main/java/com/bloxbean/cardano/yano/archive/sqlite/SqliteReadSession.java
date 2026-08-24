package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

final class SqliteReadSession implements ArchiveReadSession {
    private final SqliteHistoryArchiveBackend backend;
    private final long generation;
    private final Connection connection;
    private final long readerTicket;
    private final AtomicBoolean closed = new AtomicBoolean();

    SqliteReadSession(SqliteHistoryArchiveBackend backend, long generation, Connection connection,
                      long readerTicket) {
        this.backend = backend;
        this.generation = generation;
        this.connection = connection;
        this.readerTicket = readerTicket;
    }

    @Override
    public long generation() {
        return generation;
    }

    Connection connection() {
        if (closed.get()) throw new IllegalStateException("SQLite read session is closed");
        return connection;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        } finally {
            try { connection.close(); } catch (SQLException ignored) { }
            backend.releaseReader(readerTicket);
        }
    }
}
