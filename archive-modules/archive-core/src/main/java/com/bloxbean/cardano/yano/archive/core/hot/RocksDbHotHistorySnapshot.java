package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Snapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    public List<Entry> queryTable(ArchiveDatasetId dataset, String table, Map<String, Object> filters,
                                  Long blockFromInclusive, Long blockToInclusive) {
        requireOpen();
        byte[] logicalPrefix = ("archive-row/" + table + "/").getBytes(StandardCharsets.UTF_8);
        byte[] base = ("d/" + dataset.name() + "/").getBytes(StandardCharsets.UTF_8);
        byte[] physical = Arrays.copyOf(base, base.length + logicalPrefix.length);
        System.arraycopy(logicalPrefix, 0, physical, base.length, logicalPrefix.length);
        List<Entry> result = new ArrayList<>();
        try (var iterator = database.newIterator(options)) {
            for (iterator.seek(physical); iterator.isValid() && startsWith(iterator.key(), physical);
                 iterator.next()) {
                var row = HotArchiveRows.decode(iterator.value());
                boolean matches = filters.entrySet().stream().allMatch(filter -> same(
                        row.value(filter.getKey()), filter.getValue()));
                Object coordinate = row.value("block_number");
                boolean inRange = blockFromInclusive == null || coordinate instanceof Number number
                        && number.longValue() >= blockFromInclusive && number.longValue() <= blockToInclusive;
                if (matches && inRange) result.add(new Entry(
                        Arrays.copyOfRange(iterator.key(), base.length, iterator.key().length), row));
            }
        }
        return List.copyOf(result);
    }

    private static boolean same(Object left, Object right) {
        return left instanceof byte[] a && right instanceof byte[] b ? Arrays.equals(a, b)
                : java.util.Objects.equals(left, right);
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
