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
            return new HotHistoryMutation(key, encode(row));
        } catch (Exception e) { throw new IllegalStateException("cannot encode live archive key", e); }
    }

    public static List<ArchiveRecord> read(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                           String table, Map<String, Object> filters) {
        byte[] prefix = ("archive-row/" + table + "/").getBytes(StandardCharsets.UTF_8);
        List<ArchiveRecord> result = new ArrayList<>();
        for (var entry : snapshot.scan(dataset, prefix)) {
            ArchiveRecord row = decode(entry.value());
            boolean matches = filters.entrySet().stream().allMatch(filter -> valuesEqual(
                    row.value(filter.getKey()), filter.getValue()));
            if (matches) result.add(row);
        }
        return result;
    }

    public static List<ArchiveRecord> rowsInRange(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                                  String table, long blockFromInclusive,
                                                  long blockToInclusive) {
        if (blockFromInclusive < 0 || blockToInclusive < blockFromInclusive) {
            throw new IllegalArgumentException("invalid hot-history block range");
        }
        byte[] prefix = ("archive-row/" + table + "/").getBytes(StandardCharsets.UTF_8);
        List<ArchiveRecord> rows = new ArrayList<>();
        for (var entry : snapshot.scan(dataset, prefix)) {
            ArchiveRecord row = decode(entry.value());
            Object coordinate = row.value("block_number");
            if (coordinate == null) coordinate = row.value("first_seen_block_number");
            if (coordinate instanceof Number number && number.longValue() >= blockFromInclusive
                    && number.longValue() <= blockToInclusive) rows.add(row);
        }
        return List.copyOf(rows);
    }

    public static List<ArchiveRecord> allRows(HotHistorySnapshot snapshot, ArchiveDatasetId dataset,
                                              String table) {
        byte[] prefix = ("archive-row/" + table + "/").getBytes(StandardCharsets.UTF_8);
        return snapshot.scan(dataset, prefix).stream().map(entry -> decode(entry.value())).toList();
    }

    public static List<byte[]> allKeys(HotHistorySnapshot snapshot, ArchiveDatasetId dataset, String table) {
        byte[] prefix = ("archive-row/" + table + "/").getBytes(StandardCharsets.UTF_8);
        return snapshot.scan(dataset, prefix).stream().map(HotHistorySnapshot.Entry::logicalKey).toList();
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
        byte[] prefix = ("archive-row/" + table + "/").getBytes(StandardCharsets.UTF_8);
        List<byte[]> keys = new ArrayList<>();
        for (var entry : snapshot.scan(dataset, prefix)) {
            ArchiveRecord row = decode(entry.value());
            Object coordinate = row.value("block_number");
            if (coordinate == null) coordinate = row.value("first_seen_block_number");
            if (coordinate instanceof Number number && number.longValue() >= blockFromInclusive
                    && number.longValue() <= blockToInclusive) {
                keys.add(entry.logicalKey());
            }
        }
        return keys;
    }

    /**
     * Address dimension rows are shared by the live and backfill tracks. Keep
     * the earliest observation, while failing closed if a digest key ever maps
     * to different immutable address attributes.
     */
    public static byte[] mergeAddressDimensionValue(byte[] previous, byte[] candidate) {
        if (previous == null) return candidate;
        ArchiveRecord oldRow = decode(previous);
        ArchiveRecord newRow = decode(candidate);
        if (!oldRow.table().equals("addresses") || !newRow.table().equals("addresses")) {
            throw new IllegalArgumentException("address dimension rows required");
        }
        List<String> immutable = List.of("address_key", "raw_address", "display_address", "network_id",
                "address_type", "payment_credential_type", "payment_credential", "stake_reference_type",
                "stake_credential_type", "stake_credential", "pointer_slot", "pointer_tx_index",
                "pointer_cert_index");
        for (String column : immutable) {
            if (!valuesEqual(oldRow.value(column), newRow.value(column))) {
                throw new ArchiveStoreException("address dimension collision for column " + column);
            }
        }
        long oldBlock = ((Number) oldRow.value("first_seen_block_number")).longValue();
        long newBlock = ((Number) newRow.value("first_seen_block_number")).longValue();
        if (newBlock != oldBlock) return newBlock < oldBlock ? candidate : previous;
        long oldSlot = ((Number) oldRow.value("first_seen_slot")).longValue();
        long newSlot = ((Number) newRow.value("first_seen_slot")).longValue();
        if (newSlot != oldSlot) return newSlot < oldSlot ? candidate : previous;
        long oldEpoch = ((Number) oldRow.value("first_seen_epoch")).longValue();
        long newEpoch = ((Number) newRow.value("first_seen_epoch")).longValue();
        return newEpoch < oldEpoch ? candidate : previous;
    }

    public static boolean isAddressDimensionKey(byte[] logicalKey) {
        return startsWith(logicalKey, "archive-row/addresses/".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof byte[] a && right instanceof byte[] b) return Arrays.equals(a, b);
        return Objects.equals(left, right);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static byte[] encode(ArchiveRow row) throws IOException {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeUTF(row.table()); out.writeInt(row.values().size());
            for (Object value : row.values()) writeValue(out, value);
            return bytes.toByteArray();
        }
    }

    private static ArchiveRecord decode(byte[] encoded) {
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
