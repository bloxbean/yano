package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.api.schema.*;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compact backend-neutral row encoding for the bounded live RocksDB window. */
public final class HotArchiveRows {
    private static final byte[] PREFIX = "archive-row/".getBytes(StandardCharsets.UTF_8);
    private HotArchiveRows() { }

    public static HotHistoryMutation put(ArchiveDatasetId dataset, ArchiveRow row) {
        return new HotHistoryMutation(key(dataset, row), encode(row));
    }

    /** Stable digest of a logical table primary key, used only for hot ownership/cleanup. */
    public static byte[] key(ArchiveDatasetId dataset, ArchiveRow row) {
        ArchiveTableSchema table = ArchiveSchemas.schema(dataset).tables().stream()
                .filter(candidate -> candidate.physicalName().equals(row.table())).findFirst().orElseThrow();
        if (row.values().size() != table.columns().size()) throw new IllegalArgumentException("row shape mismatch");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < table.columns().size(); i++) index.put(table.columns().get(i).name(), i);
            for (String key : table.primaryKey()) writeValue(new DataOutputStream(new DigestStream(digest)),
                    row.values().get(index.get(key)));
            byte[] tablePrefix = ("archive-row/" + row.table() + "/").getBytes(StandardCharsets.UTF_8);
            byte[] key = Arrays.copyOf(tablePrefix, tablePrefix.length + digest.getDigestLength());
            System.arraycopy(digest.digest(), 0, key, tablePrefix.length, digest.getDigestLength());
            return key;
        } catch (Exception e) { throw new IllegalStateException("cannot encode live archive key", e); }
    }

    public static List<ArchiveRecord> read(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                           String table, Map<String, Object> filters) {
        List<ArchiveRecord> result = new ArrayList<>();
        for (var entry : snapshot.queryTable(dataset, table, filters, null, null)) result.add(entry.row());
        return result;
    }

    public static List<ArchiveRecord> rowsInRange(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                                  String table, long blockFromInclusive,
                                                  long blockToInclusive) {
        if (blockFromInclusive < 0 || blockToInclusive < blockFromInclusive) {
            throw new IllegalArgumentException("invalid hot-history block range");
        }
        List<ArchiveRecord> rows = new ArrayList<>();
        for (var entry : snapshot.queryTable(dataset, table, Map.of(), blockFromInclusive,
                blockToInclusive)) rows.add(entry.row());
        rows.sort(recordComparator(dataset));
        return List.copyOf(rows);
    }

    public static List<ArchiveRecord> allRows(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                              String table) {
        return snapshot.scanTable(dataset, table).stream().map(HotHistorySnapshot.Entry::row).toList();
    }

    public static List<byte[]> allKeys(HotHistorySnapshot snapshot, ArchiveDatasetId dataset, String table) {
        return snapshot.scanTable(dataset, table).stream().map(HotHistorySnapshot.Entry::logicalKey).toList();
    }

    public static List<byte[]> keysThrough(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                           String table, long blockInclusive) {
        return keysInRange(snapshot, dataset, table, 0, blockInclusive);
    }

    /** Returns only rows whose canonical block coordinate is inside the supplied complete range. */
    public static List<byte[]> keysInRange(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                           String table, long blockFromInclusive, long blockToInclusive) {
        if (blockFromInclusive < 0 || blockToInclusive < blockFromInclusive) {
            throw new IllegalArgumentException("invalid hot-history block range");
        }
        List<byte[]> keys = new ArrayList<>();
        for (var entry : snapshot.queryTable(dataset, table, Map.of(), blockFromInclusive,
                blockToInclusive)) keys.add(entry.logicalKey());
        return keys;
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof byte[] a && right instanceof byte[] b) return Arrays.equals(a, b);
        return Objects.equals(left, right);
    }

    private static Comparator<ArchiveRecord> recordComparator(ArchiveDatasetId dataset) {
        List<String> columns = ArchiveSchemas.schema(dataset).paginationOrder();
        return (left, right) -> {
            for (String column : columns) {
                int compared = compareValue(left.value(column), right.value(column));
                if (compared != 0) return compared;
            }
            return 0;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareValue(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if (left instanceof byte[] a && right instanceof byte[] b) {
            return Arrays.compareUnsigned(a, b);
        }
        if (left instanceof Number a && right instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return left.toString().compareTo(right.toString());
    }


    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    public static byte[] encode(ArchiveRow row) {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeUTF(row.table()); out.writeInt(row.values().size());
            for (Object value : row.values()) writeValue(out, value);
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("cannot encode live archive row", e); }
    }

    public static ArchiveRecord decode(byte[] encoded) {
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            String tableName = in.readUTF();
            ArchiveTableSchema table = ArchiveSchemas.all().values().stream().flatMap(s -> s.tables().stream())
                    .filter(candidate -> candidate.physicalName().equals(tableName)).findFirst().orElseThrow();
            int count = in.readInt();
            if (count != table.columns().size()) throw new IOException("row shape mismatch");
            Map<String, Object> values = new LinkedHashMap<>();
            for (ArchiveColumn column : table.columns()) values.put(column.name(), readValue(in));
            return new ArchiveRecord(tableName, values);
        } catch (Exception e) { throw new IllegalStateException("cannot decode live archive row", e); }
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) { out.writeByte(0); return; }
        if (value instanceof byte[] bytes) { out.writeByte(1); out.writeInt(bytes.length); out.write(bytes); return; }
        if (value instanceof String text) { out.writeByte(2); out.writeUTF(text); return; }
        if (value instanceof Integer integer) { out.writeByte(3); out.writeInt(integer); return; }
        if (value instanceof Long number) { out.writeByte(4); out.writeLong(number); return; }
        if (value instanceof Boolean bool) { out.writeByte(5); out.writeBoolean(bool); return; }
        if (value instanceof BigInteger integer) { out.writeByte(6); out.writeUTF(integer.toString()); return; }
        if (value instanceof BigDecimal decimal) { out.writeByte(7); out.writeUTF(decimal.toPlainString()); return; }
        if (value instanceof UUID uuid) { out.writeByte(8); out.writeLong(uuid.getMostSignificantBits()); out.writeLong(uuid.getLeastSignificantBits()); return; }
        if (value instanceof Number number) { out.writeByte(4); out.writeLong(number.longValue()); return; }
        throw new IOException("unsupported row value " + value.getClass());
    }

    private static Object readValue(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> { int size = in.readInt(); if (size < 0 || size > 64 * 1024 * 1024) throw new IOException("size"); yield in.readNBytes(size); }
            case 2 -> in.readUTF(); case 3 -> in.readInt(); case 4 -> in.readLong(); case 5 -> in.readBoolean();
            case 6 -> new BigInteger(in.readUTF()); case 7 -> new BigDecimal(in.readUTF());
            case 8 -> new UUID(in.readLong(), in.readLong()); default -> throw new IOException("type");
        };
    }

    private static final class DigestStream extends OutputStream {
        private final MessageDigest digest;
        private DigestStream(MessageDigest digest) { this.digest = digest; }
        @Override public void write(int value) { digest.update((byte) value); }
        @Override public void write(byte[] value, int offset, int length) { digest.update(value, offset, length); }
    }
}
