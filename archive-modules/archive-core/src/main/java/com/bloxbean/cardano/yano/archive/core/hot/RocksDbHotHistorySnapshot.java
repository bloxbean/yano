package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Snapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** RocksDB implementation hidden behind the backend-neutral snapshot contract. */
final class RocksDbHotHistorySnapshot implements HotHistorySnapshot {
    private final RocksDB database;
    private final Snapshot snapshot;
    private final ReadOptions options;
    private final AtomicBoolean closed = new AtomicBoolean();

    RocksDbHotHistorySnapshot(RocksDB database) {
        this.database = database;
        this.snapshot = database.getSnapshot();
        this.options = new ReadOptions().setSnapshot(snapshot);
    }

    @Override
    public List<Entry> scan(ArchiveDatasetId dataset, byte[] logicalPrefix) {
        requireOpen();
        byte[] base = ("d/" + dataset.name() + "/").getBytes(StandardCharsets.UTF_8);
        byte[] physical = Arrays.copyOf(base, base.length + logicalPrefix.length);
        System.arraycopy(logicalPrefix, 0, physical, base.length, logicalPrefix.length);
        List<Entry> result = new ArrayList<>();
        try (var iterator = database.newIterator(options)) {
            for (iterator.seek(physical); iterator.isValid() && startsWith(iterator.key(), physical);
                 iterator.next()) {
                result.add(new Entry(Arrays.copyOfRange(iterator.key(), base.length, iterator.key().length),
                        iterator.value()));
            }
        }
        return List.copyOf(result);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("hot-history snapshot is closed");
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        options.close();
        database.releaseSnapshot(snapshot);
    }
}
