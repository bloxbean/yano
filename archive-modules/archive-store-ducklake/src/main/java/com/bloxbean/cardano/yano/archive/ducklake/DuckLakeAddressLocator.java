package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rebuildable point-lookup accelerator for the immutable DuckLake address dimension. */
final class DuckLakeAddressLocator implements AutoCloseable {
    record Entry(List<Object> values, byte[] key, byte[] immutableDigest,
                 long firstSeenBlock, long firstSeenSlot, long firstSeenEpoch) {
        Entry(List<Object> source) {
            this(copyValues(source), bytes(source.get(0)), digest(source), number(source.get(13)),
                    number(source.get(14)), number(source.get(15)));
            if (source.size() != 16) throw new IllegalArgumentException("address dimension row must have 16 values");
        }

        Entry {
            values = copyValues(values);
            key = key.clone();
            immutableDigest = immutableDigest.clone();
        }

        @Override public List<Object> values() { return copyValues(values); }
        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] immutableDigest() { return immutableDigest.clone(); }

        private static List<Object> copyValues(List<Object> values) {
            List<Object> result = new ArrayList<>(values.size());
            for (Object value : values) result.add(value instanceof byte[] bytes ? bytes.clone() : value);
            return Collections.unmodifiableList(result);
        }

        private static byte[] bytes(Object value) {
            if (!(value instanceof byte[] bytes) || bytes.length == 0) {
                throw new IllegalArgumentException("address key is required");
            }
            return bytes.clone();
        }

        private static long number(Object value) {
            if (!(value instanceof Number number)) throw new IllegalArgumentException("first-seen coordinate required");
            return number.longValue();
        }

        private static byte[] digest(List<Object> values) {
            if (values.size() != 16) throw new IllegalArgumentException("address dimension row must have 16 values");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (int i = 0; i < 13; i++) {
                    Object value = values.get(i);
                    if (value == null) {
                        digest.update((byte) 0);
                    } else if (value instanceof byte[] bytes) {
                        digest.update((byte) 1);
                        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                        digest.update(bytes);
                    } else {
                        byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
                        digest.update((byte) 2);
                        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
                        digest.update(encoded);
                    }
                }
                return digest.digest();
            } catch (Exception e) {
                throw new IllegalStateException("cannot digest address dimension row", e);
            }
        }
    }

    record PlannedEntry(Entry entry, boolean replace) { }
    record Plan(List<PlannedEntry> accepted) {
        Plan { accepted = List.copyOf(accepted); }
        static Plan empty() { return new Plan(List.of()); }
    }

    private record Key(byte[] value) {
        private Key { value = value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key that && Arrays.equals(value, that.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    private final String jdbcUrl;

    DuckLakeAddressLocator(Path catalogPath) {
        Path path = catalogPath.resolveSibling(catalogPath.getFileName() + ".address-locator.sqlite");
        jdbcUrl = "jdbc:sqlite:" + path.toAbsolutePath().normalize();
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new ArchiveStoreException("SQLite locator driver unavailable", e); }
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS address_locator(address_key BLOB PRIMARY KEY, "
                    + "immutable_digest BLOB NOT NULL, first_seen_block INTEGER NOT NULL, "
                    + "first_seen_slot INTEGER NOT NULL, first_seen_epoch INTEGER NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS address_locator_meta("
                    + "singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL)");
            sql.execute("INSERT OR IGNORE INTO address_locator_meta VALUES(1,-1)");
        } catch (SQLException e) {
            throw new ArchiveStoreException("cannot initialize address locator", e);
        }
    }

    synchronized Plan plan(Connection duckLake, long generation, Collection<Entry> candidates) {
        rebuildIfRequired(duckLake, generation);
        Map<Key, Entry> unique = new LinkedHashMap<>();
        for (Entry candidate : candidates) {
            Key key = new Key(candidate.key());
            Entry previous = unique.get(key);
            if (previous != null && !Arrays.equals(previous.immutableDigest(), candidate.immutableDigest())) {
                throw new ArchiveStoreException("address dimension conflict within archive job");
            }
            if (previous == null || earlier(candidate, previous)) unique.put(key, candidate);
        }
        List<PlannedEntry> accepted = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(
                "SELECT immutable_digest,first_seen_block,first_seen_slot,first_seen_epoch "
                        + "FROM address_locator WHERE address_key=?")) {
            for (Entry candidate : unique.values()) {
                query.setBytes(1, candidate.key());
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) {
                        accepted.add(new PlannedEntry(candidate, false));
                    } else {
                        if (!Arrays.equals(row.getBytes(1), candidate.immutableDigest())) {
                            throw new ArchiveStoreException("address dimension conflict for canonical address key");
                        }
                        long block = row.getLong(2);
                        long slot = row.getLong(3);
                        long epoch = row.getLong(4);
                        if (earlier(candidate, block, slot, epoch)) {
                            accepted.add(new PlannedEntry(candidate, true));
                        }
                    }
                }
            }
            return new Plan(accepted);
        } catch (SQLException e) {
            throw new ArchiveStoreException("address locator query failed", e);
        }
    }

    synchronized void advance(long generation, Plan plan) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO address_locator VALUES(?,?,?,?,?) ON CONFLICT(address_key) DO UPDATE SET "
                            + "immutable_digest=excluded.immutable_digest,first_seen_block=excluded.first_seen_block,"
                            + "first_seen_slot=excluded.first_seen_slot,first_seen_epoch=excluded.first_seen_epoch")) {
                for (PlannedEntry planned : plan.accepted()) {
                    bind(upsert, planned.entry());
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
            setGeneration(connection, generation);
            connection.commit();
        } catch (SQLException e) {
            throw new ArchiveStoreException("address locator update failed", e);
        }
    }

    synchronized void rebuildIfRequired(Connection duckLake, long generation) {
        try (Connection locator = open()) {
            if (generation(locator) == generation) return;
        } catch (SQLException e) {
            throw new ArchiveStoreException("address locator generation read failed", e);
        }
        rebuild(duckLake, generation);
    }

    synchronized void rebuild(Connection duckLake, long generation) {
        try (Connection locator = open()) {
            locator.setAutoCommit(false);
            try (Statement clear = locator.createStatement()) { clear.executeUpdate("DELETE FROM address_locator"); }
            try (Statement scan = duckLake.createStatement();
                 ResultSet rows = scan.executeQuery("SELECT * FROM history_lake.addresses");
                 PreparedStatement insert = locator.prepareStatement("INSERT INTO address_locator VALUES(?,?,?,?,?)")) {
                int pending = 0;
                while (rows.next()) {
                    List<Object> values = Arrays.asList(
                            rows.getBytes(1), rows.getBytes(2), rows.getString(3), rows.getObject(4),
                            rows.getString(5), rows.getString(6), rows.getBytes(7), rows.getString(8),
                            rows.getString(9), rows.getBytes(10), rows.getObject(11), rows.getObject(12),
                            rows.getObject(13), rows.getObject(14), rows.getObject(15), rows.getObject(16));
                    Entry entry = new Entry(values);
                    bind(insert, entry);
                    insert.addBatch();
                    if (++pending == 10_000) { insert.executeBatch(); pending = 0; }
                }
                if (pending > 0) insert.executeBatch();
            }
            setGeneration(locator, generation);
            locator.commit();
        } catch (SQLException e) {
            throw new ArchiveStoreException("address locator rebuild failed", e);
        }
    }

    private static void bind(PreparedStatement statement, Entry entry) throws SQLException {
        statement.setBytes(1, entry.key());
        statement.setBytes(2, entry.immutableDigest());
        statement.setLong(3, entry.firstSeenBlock());
        statement.setLong(4, entry.firstSeenSlot());
        statement.setLong(5, entry.firstSeenEpoch());
    }

    private static boolean earlier(Entry left, Entry right) {
        return earlier(left, right.firstSeenBlock(), right.firstSeenSlot(), right.firstSeenEpoch());
    }

    private static boolean earlier(Entry left, long block, long slot, long epoch) {
        if (left.firstSeenBlock() != block) return left.firstSeenBlock() < block;
        if (left.firstSeenSlot() != slot) return left.firstSeenSlot() < slot;
        return left.firstSeenEpoch() < epoch;
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("PRAGMA synchronous=FULL");
            sql.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private long generation(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(
                "SELECT generation FROM address_locator_meta WHERE singleton=1")) {
            row.next();
            return row.getLong(1);
        }
    }

    private void setGeneration(Connection connection, long generation) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE address_locator_meta SET generation=? WHERE singleton=1")) {
            update.setLong(1, generation);
            update.executeUpdate();
        }
    }

    @Override public void close() { }
}
