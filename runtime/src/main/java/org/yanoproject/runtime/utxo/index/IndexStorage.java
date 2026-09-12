package org.yanoproject.runtime.utxo.index;

import org.yanoproject.api.utxo.index.IndexReader;
import org.yanoproject.api.utxo.index.IndexWriter;
import org.yanoproject.runtime.db.RocksDbContext;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Namespace enforcement and native-handle lifetime live here, not in feature code. */
public final class IndexStorage {
    public static final String DATA = "utxo_index_data";
    public static final String UNDO = "utxo_index_undo";
    public static final String META = "utxo_index_meta";
    private final Supplier<RocksDbContext> context;
    private final String id;
    private final Map<String, String> internalTables;

    public IndexStorage(Supplier<RocksDbContext> context, String id) {
        this(context, id, Map.of());
    }

    /** Runtime-only binding for built-in physical tables, never exposed to providers. */
    public IndexStorage(Supplier<RocksDbContext> context, String id, Map<String, String> internalTables) {
        if (id == null || !id.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid contributor identity: " + id);
        }
        this.context = Objects.requireNonNull(context);
        this.id = id;
        this.internalTables = Map.copyOf(internalTables);
    }

    public Scope open(WriteBatch batch) { return new Scope(batch); }

    public static final class StorageFailure extends RuntimeException {
        public StorageFailure(RocksDBException cause) { super("Contributor storage failure", cause); }
    }

    public final class Scope implements IndexWriter, AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final WriteBatch batch;
        private boolean open = true;

        private Scope(WriteBatch batch) { this.batch = batch; }

        public void requireActive() { check(false); }

        private void check(boolean writing) {
            if (!open || Thread.currentThread() != owner) throw new IllegalStateException("Index scope expired or wrong thread");
            if (writing && batch == null) throw new IllegalStateException("Read-only index scope");
        }

        private byte[] prefix(String table) {
            if (!internalTables.isEmpty()) {
                if (!internalTables.containsKey(table)) throw new IllegalArgumentException("Table outside contributor namespace: " + table);
                return new byte[0];
            }
            if (table == null || !table.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid table: " + table);
            byte[] contributor = id.getBytes(StandardCharsets.UTF_8);
            byte[] name = table.getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(5 + contributor.length + name.length).put((byte) 1)
                    .putShort((short) contributor.length).put(contributor).putShort((short) name.length).put(name).array();
        }

        private ColumnFamilyHandle handle(String table) {
            String physical = internalTables.isEmpty() ? ("undo".equals(table) ? UNDO : DATA) : internalTables.get(table);
            return Objects.requireNonNull(context.get().handle(physical), "Missing contributor table " + physical);
        }

        @Override public byte[] getCommitted(String table, byte[] key) {
            check(false);
            byte[] encoded = join(prefix(table), key);
            try { return context.get().db().get(handle(table), encoded); }
            catch (RocksDBException failure) { throw new StorageFailure(failure); }
        }

        @Override public void put(String table, byte[] key, byte[] value) {
            check(true);
            byte[] encoded = join(prefix(table), key);
            try { batch.put(handle(table), encoded, Objects.requireNonNull(value)); }
            catch (RocksDBException failure) { throw new StorageFailure(failure); }
        }

        @Override public void delete(String table, byte[] key) {
            check(true);
            byte[] encoded = join(prefix(table), key);
            try { batch.delete(handle(table), encoded); }
            catch (RocksDBException failure) { throw new StorageFailure(failure); }
        }

        @Override public void deleteRange(String table, byte[] from, byte[] to) {
            check(true);
            byte[] namespace = prefix(table);
            if (Arrays.compareUnsigned(from, to) > 0) throw new IllegalArgumentException("Reversed range");
            try { batch.deleteRange(handle(table), join(namespace, from), join(namespace, to)); }
            catch (RocksDBException failure) { throw new StorageFailure(failure); }
        }

        @Override public IndexReader.Page scanCommitted(String table, byte[] keyPrefix, String cursor, int limit) {
            check(false);
            if (limit < 1 || limit > 256) throw new IllegalArgumentException("Page limit must be 1..256");
            byte[] namespace = prefix(table);
            byte[] seek = join(namespace, keyPrefix);
            byte[] after = cursor == null ? null : Base64.getUrlDecoder().decode(cursor);
            if (after != null && !startsWith(after, seek)) throw new IllegalArgumentException("Cursor outside requested namespace/prefix");
            var entries = new ArrayList<IndexReader.Entry>();
            try (RocksIterator it = context.get().db().newIterator(handle(table))) {
                it.seek(after == null ? seek : after);
                if (after != null && it.isValid() && Arrays.equals(it.key(), after)) it.next();
                byte[] last = null;
                while (it.isValid() && startsWith(it.key(), seek) && entries.size() < limit) {
                    last = it.key();
                    entries.add(new IndexReader.Entry(Arrays.copyOfRange(last, namespace.length, last.length), it.value()));
                    it.next();
                }
                it.status();
                String next = last != null && it.isValid() && startsWith(it.key(), seek)
                        ? Base64.getUrlEncoder().withoutPadding().encodeToString(last) : null;
                return new IndexReader.Page(entries, next);
            } catch (RocksDBException failure) { throw new StorageFailure(failure); }
        }

        @Override public void close() { check(false); open = false; }
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] join(byte[] prefix, byte[] key) {
        Objects.requireNonNull(key);
        byte[] joined = Arrays.copyOf(prefix, prefix.length + key.length);
        System.arraycopy(key, 0, joined, prefix.length, key.length);
        return joined;
    }
}
