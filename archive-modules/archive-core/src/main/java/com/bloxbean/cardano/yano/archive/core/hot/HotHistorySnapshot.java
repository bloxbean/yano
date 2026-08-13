package com.bloxbean.cardano.yano.archive.core.hot;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Snapshot;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    public List<Entry> scan( com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset,
                             byte[] logicalPrefix) {
        if (closed.get()) throw new IllegalStateException("hot-history snapshot is closed");
        byte[] base = ("d/" + dataset.name() + "/").getBytes(StandardCharsets.UTF_8);
        byte[] physical = Arrays.copyOf(base, base.length + logicalPrefix.length);
        System.arraycopy(logicalPrefix, 0, physical, base.length, logicalPrefix.length);
        List<Entry> result = new ArrayList<>();
        try (var iterator = database.newIterator(options)) {
            for (iterator.seek(physical); iterator.isValid() && startsWith(iterator.key(), physical); iterator.next()) {
                result.add(new Entry(Arrays.copyOfRange(iterator.key(), base.length, iterator.key().length),
                        iterator.value().clone()));
            }
        }
        return List.copyOf(result);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    public record Entry(byte[] logicalKey, byte[] value) {
        public Entry { logicalKey = logicalKey.clone(); value = value.clone(); }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        options.close();
        database.releaseSnapshot(snapshot);
    }
}
