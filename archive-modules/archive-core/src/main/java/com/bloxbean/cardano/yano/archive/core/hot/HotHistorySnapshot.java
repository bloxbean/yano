package com.bloxbean.cardano.yano.archive.core.hot;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Snapshot;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HotHistorySnapshot implements AutoCloseable {
    private final RocksDB database;
    private final Snapshot snapshot;
    private final ReadOptions options;
    private final AtomicBoolean closed = new AtomicBoolean();

    HotHistorySnapshot(RocksDB database) {
        this.database = database;
        this.snapshot = database.getSnapshot();
        this.options = new ReadOptions().setSnapshot(snapshot);
    }

    public Optional<byte[]> get(byte[] key) {
        if (closed.get()) throw new IllegalStateException("hot-history snapshot is closed");
        try {
            return Optional.ofNullable(database.get(options, key));
        } catch (org.rocksdb.RocksDBException e) {
            throw new IllegalStateException("hot-history snapshot read failed", e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        options.close();
        database.releaseSnapshot(snapshot);
    }
}
