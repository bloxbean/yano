package org.yanoproject.runtime.wallet;

import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.exception.AddressExcepion;
import org.yanoproject.api.wallet.AddressFirstSeen;
import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.wallet.WalletIndexCoverage;
import org.yanoproject.api.wallet.WalletIndexUnavailableException;
import org.yanoproject.api.genesis.GenesisUtxo;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.runtime.db.RocksDbContext;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.yanoproject.api.utxo.index.IndexWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * Derived indexes staged in the owning UTxO batch. The owner serializes apply,
 * rollback and reads; this class keeps no pre-commit mutable coverage cache.
 */
public final class WalletIndexStore {
    public static final byte FIRST_SEEN = 1;
    public static final byte FILTERS = 2;
    private static final int VERSION = 1;
    private final RocksDB db;
    private final ColumnFamilyHandle firstSeen;
    private final ColumnFamilyHandle filters;
    private final ColumnFamilyHandle meta;
    private final ColumnFamilyHandle undo;
    private final ColumnFamilyHandle genesis;
    private final ColumnFamilyHandle errors;
    private final boolean firstSeenEnabled;
    private final boolean filtersEnabled;

    public WalletIndexStore(RocksDbContext context, boolean firstSeenEnabled, boolean filtersEnabled) {
        this.db = context.db();
        this.firstSeen = context.handle(WalletIndexCf.FIRST_SEEN);
        this.filters = context.handle(WalletIndexCf.FILTERS);
        this.meta = context.handle(WalletIndexCf.META);
        this.undo = context.handle(WalletIndexCf.UNDO);
        this.genesis = context.handle(WalletIndexCf.GENESIS);
        this.errors = context.handle(WalletIndexCf.ERRORS);
        this.firstSeenEnabled = firstSeenEnabled;
        this.filtersEnabled = filtersEnabled;
        if (enabled() && (firstSeen == null || filters == null || meta == null || undo == null || errors == null)) {
            throw new IllegalStateException("Wallet index column families are missing");
        }
    }

    public boolean enabled() { return firstSeenEnabled || filtersEnabled; }
    public boolean filtersEnabled() { return filtersEnabled; }

    public boolean hasStoredMetadata() {
        try { return db.get(meta, new byte[]{FIRST_SEEN}) != null || db.get(meta, new byte[]{FILTERS}) != null; }
        catch (RocksDBException failure) { throw new IllegalStateException("Cannot read wallet metadata", failure); }
    }

    public record FilterRecord(ChainPoint point, byte[] filter, String error) {
        public FilterRecord(ChainPoint point, byte[] filter) { this(point, filter, null); }
    }

    private void stageError(IndexWriter batch, byte feature, ChainPoint point, String reason) throws RocksDBException {
        byte[] detail = reason.getBytes(StandardCharsets.UTF_8);
        batch.put(WalletIndexCf.ERRORS, undoKey(feature, point.blockNumber()), ByteBuffer.allocate(32 + detail.length)
                .put(HexFormat.of().parseHex(point.blockHash())).put(detail).array());
    }

    private String blockError(ChainPoint point) throws RocksDBException {
        for (byte feature : new byte[]{FILTERS, FIRST_SEEN}) {
            byte[] value = db.get(errors, undoKey(feature, point.blockNumber()));
            if (value == null) continue;
            if (value.length < 32 || !HexFormat.of().formatHex(value, 0, 32).equals(point.blockHash())) {
                throw new IllegalStateException("Wallet error record does not match canonical filter");
            }
            return new String(value, 32, value.length - 32, StandardCharsets.UTF_8);
        }
        return null;
    }

    private boolean hasFirstSeenErrors(long through) throws RocksDBException {
        try (RocksIterator it = db.newIterator(errors)) {
            it.seek(undoKey(FIRST_SEEN, 0));
            boolean found = it.isValid() && it.key()[0] == FIRST_SEEN
                    && ByteBuffer.wrap(it.key(), 1, 8).getLong() <= through;
            it.status();
            return found;
        }
    }

    /** Short-lived iterator: no native handles survive an owner snapshot reinitialization. */
    public List<FilterRecord> readFilters(long afterBlock, long toBlock, int limit) throws RocksDBException {
        List<FilterRecord> records = new ArrayList<>();
        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator it = db.newIterator(filters, options)) {
            it.seek(number(Math.max(0, afterBlock + 1)));
            while (it.isValid() && records.size() < limit) {
                long block = ByteBuffer.wrap(it.key()).getLong();
                if (block > toBlock) break;
                byte[] value = it.value();
                if (value.length < 61) throw new IllegalStateException("Truncated filter record");
                ChainPoint point = new ChainPoint(block, ByteBuffer.wrap(value).getLong(),
                        HexFormat.of().formatHex(value, 8, 40));
                records.add(new FilterRecord(point, Arrays.copyOfRange(value, 40, value.length)));
                it.next();
            }
            it.status();
        }
        if (!records.isEmpty() && hasErrorsBetween(records.getFirst().point().blockNumber(),
                records.getLast().point().blockNumber())) {
            for (int i = 0; i < records.size(); i++) {
                FilterRecord record = records.get(i);
                records.set(i, new FilterRecord(record.point(), record.filter(), blockError(record.point())));
            }
        }
        return records;
    }

    /** Two prefix probes per page; the normal error-free scan does no per-block error gets. */
    private boolean hasErrorsBetween(long from, long through) throws RocksDBException {
        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator iterator = db.newIterator(errors, options)) {
            for (byte feature : new byte[]{FILTERS, FIRST_SEEN}) {
                iterator.seek(undoKey(feature, from));
                boolean found = iterator.isValid() && iterator.key().length == 9
                        && iterator.key()[0] == feature
                        && ByteBuffer.wrap(iterator.key(), 1, 8).getLong() <= through;
                iterator.status();
                if (found) return true;
            }
            return false;
        }
    }

    public void stageScanGenesis(IndexWriter batch, List<GenesisUtxo> outputs) throws RocksDBException {
        if (!filtersEnabled) return;
        for (GenesisUtxo output : outputs) {
            if (output.isByron()) continue; // No supported payment/stake credentials in Byron genesis.
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF(output.address());
                out.writeUTF(output.amount().toString());
                batch.put(WalletIndexCf.GENESIS, HexFormat.of().parseHex(output.txHash()), bytes.toByteArray());
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public List<Utxo> scanGenesis() throws RocksDBException {
        List<Utxo> outputs = new ArrayList<>();
        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator it = db.newIterator(genesis, options)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                try {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(it.value()));
                    outputs.add(new Utxo(new Outpoint(HexFormat.of().formatHex(it.key()), 0), in.readUTF(),
                            new BigInteger(in.readUTF()), List.of(), null, null, null, null, false,
                            0, 0, ChainPoint.ORIGIN.blockHash()));
                    if (in.available() != 0) throw new IOException("Trailing scan genesis bytes");
                } catch (IOException failure) { throw new IllegalStateException("Invalid scan genesis", failure); }
            }
            it.status();
        }
        return outputs;
    }

    public void stagePruneUndo(IndexWriter batch, long blockNumber) throws RocksDBException {
        if (undo == null) return;
        if (db.get(meta, new byte[]{FIRST_SEEN}) != null) batch.delete(WalletIndexCf.UNDO, undoKey(FIRST_SEEN, blockNumber));
        if (db.get(meta, new byte[]{FILTERS}) != null) batch.delete(WalletIndexCf.UNDO, undoKey(FILTERS, blockNumber));
    }

    /** Only called by the owner's fresh, atomic genesis initialization. */
    public void stageGenesis(IndexWriter batch, String identity, Collection<String> addresses)
            throws RocksDBException {
        for (byte feature : new byte[]{FIRST_SEEN, FILTERS}) {
            if (!enabled(feature)) continue;
            if (db.get(meta, new byte[]{feature}) != null) {
                throw new IllegalStateException("Wallet genesis coverage already exists");
            }
            batch.put(WalletIndexCf.META, new byte[]{feature}, encodeState(new State(true,
                    ChainPoint.ORIGIN, ChainPoint.ORIGIN, identity, null)));
        }
        if (firstSeenEnabled) {
            for (String address : addresses) batch.put(WalletIndexCf.FIRST_SEEN, addressBytes(address), number(0));
        }
    }

    public void stageBlock(IndexWriter batch, ChainPoint previous, ChainPoint point,
                           Collection<String> createdAddresses, byte[] filter, String failure)
            throws RocksDBException {
        stageBlock(batch, previous, point, createdAddresses, filter, failure, failure);
    }

    public void stageBlock(IndexWriter batch, ChainPoint previous, ChainPoint point,
                           Collection<String> createdAddresses, byte[] filter,
                           String firstSeenFailure, String filterFailure) throws RocksDBException {
        for (byte feature : new byte[]{FIRST_SEEN, FILTERS}) {
            if (!enabled(feature)) continue;
            byte[] key = new byte[]{feature};
            byte[] old = db.get(meta, key);
            State prior = old == null ? null : decodeState(old);
            boolean nextCanonicalNumber = previous.equals(ChainPoint.ORIGIN)
                    ? point.blockNumber() == 0 || point.blockNumber() == 1
                    : point.blockNumber() == previous.blockNumber() + 1;
            boolean contiguous = prior != null && prior.through.equals(previous)
                    && prior.reason == null && nextCanonicalNumber;
            String blockFailure = feature == FIRST_SEEN ? firstSeenFailure : filterFailure;
            if (feature == FILTERS && filter == null && blockFailure == null) blockFailure = "Filter extraction failed";
            if (blockFailure != null) stageError(batch, feature, point, blockFailure);
            // Known block errors are separate from structural history gaps (late enablement, etc.).
            String reason = feature == FIRST_SEEN && (!contiguous || !prior.complete)
                    ? "First-seen history is incomplete; enable before a fresh sync" : null;
            State next = new State(contiguous && prior.complete, contiguous ? prior.from : point,
                    point, prior == null ? "uninitialized" : prior.identity, reason);
            List<byte[]> inserted = new ArrayList<>();
            if (feature == FIRST_SEEN) {
                TreeSet<byte[]> distinct = new TreeSet<>(Arrays::compareUnsigned);
                boolean recordedError = blockFailure != null;
                for (String address : createdAddresses) {
                    try { distinct.add(addressBytes(address)); }
                    catch (IllegalArgumentException failure) {
                        if (!recordedError) {
                            stageError(batch, FIRST_SEEN, point, "Address decoding failed: " + address);
                            recordedError = true;
                        }
                    }
                }
                for (byte[] address : distinct) {
                    if (db.get(firstSeen, address) == null) {
                        batch.put(WalletIndexCf.FIRST_SEEN, address, number(point.slot()));
                        inserted.add(address);
                    }
                }
            }
            if (feature == FILTERS) {
                if (filter == null) filter = CredentialFilter.encode(new byte[16], List.of());
                byte[] hash = HexFormat.of().parseHex(point.blockHash());
                batch.put(WalletIndexCf.FILTERS, number(point.blockNumber()), ByteBuffer.allocate(40 + filter.length)
                        .putLong(point.slot()).put(hash).put(filter).array());
            }
            batch.put(WalletIndexCf.UNDO, undoKey(feature, point.blockNumber()), encodeUndo(old, inserted));
            batch.put(WalletIndexCf.META, key, encodeState(next));
        }
    }


    /** Stage all inverse operations, including indexes temporarily disabled in config. */
    public void stageRollback(IndexWriter batch, ChainPoint target) throws RocksDBException {
        if (meta == null) return;
        for (byte feature : new byte[]{FIRST_SEEN, FILTERS}) {
            // Also remove failures when undo retention is insufficient; coverage still fails closed below.
            batch.deleteRange(WalletIndexCf.ERRORS, undoKey(feature, Math.max(0, target.blockNumber() + 1)),
                    undoKey((byte) (feature + 1), 0));
            try {
                stageFeatureRollback(batch, target, feature);
            } catch (IllegalStateException invalidDerivedRecord) {
                // Malformed derived records must not prevent the canonical UTxO rollback.
                // Partial staged cleanup is safe because this feature is now unavailable.
                batch.put(WalletIndexCf.META, new byte[]{feature}, encodeState(new State(false, target, target,
                        "unknown", "Wallet rollback metadata or undo invalid; fresh sync required")));
            }
        }
    }

    private void stageFeatureRollback(IndexWriter batch, ChainPoint target, byte feature)
            throws RocksDBException {
        byte[] stateBytes = db.get(meta, new byte[]{feature});
        if (stateBytes == null) return;
        State current = decodeState(stateBytes);
        if (current.through.blockNumber() <= target.blockNumber()) return;
        boolean found = false;
        boolean missingUndo = false;
        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator it = db.newIterator(undo, options)) {
            it.seekForPrev(undoKey(feature, Long.MAX_VALUE));
            while (it.isValid() && it.key().length > 0 && it.key()[0] == feature) {
                if (it.key().length != 9) throw new IllegalStateException("Invalid wallet undo key");
                long block = ByteBuffer.wrap(it.key(), 1, 8).getLong();
                if (block <= target.blockNumber()) break;
                if (stateBytes == null || decodeState(stateBytes).through.blockNumber() != block) {
                    missingUndo = true;
                    break;
                }
                found = true;
                Undo entry = decodeUndo(it.value());
                for (byte[] address : entry.inserted) batch.delete(WalletIndexCf.FIRST_SEEN, address);
                if (feature == FILTERS) batch.delete(WalletIndexCf.FILTERS, number(block));
                stateBytes = entry.previous;
                batch.delete(WalletIndexCf.UNDO, it.key());
                it.prev();
            }
            it.status();
        }
        State restored = stateBytes == null ? null : decodeState(stateBytes);
        if (missingUndo || !found || restored != null && restored.through.blockNumber() > target.blockNumber()) {
            restored = new State(false, current.from, target, current.identity,
                    "Wallet rollback undo unavailable; fresh sync required");
        } else if (restored != null && restored.through.blockNumber() == target.blockNumber()
                && !restored.through.equals(target)) {
            restored = new State(false, current.from, target, current.identity,
                    "Wallet rollback hash mismatch; fresh sync required");
        }
        if (restored == null) batch.delete(WalletIndexCf.META, new byte[]{feature});
        else batch.put(WalletIndexCf.META, new byte[]{feature}, encodeState(restored));
    }

    public WalletIndexCoverage coverage(byte feature, ChainPoint applied) throws RocksDBException {
        if (!enabled(feature)) return new WalletIndexCoverage(false, false, null, null, null, "Index disabled");
        byte[] encoded = db.get(meta, new byte[]{feature});
        if (encoded == null) return new WalletIndexCoverage(true, false, null, null, null,
                "Index missing; enable before a fresh sync");
        State state;
        try { state = decodeState(encoded); }
        catch (IllegalStateException invalid) {
            return new WalletIndexCoverage(true, false, null, null, null,
                    "Invalid wallet index metadata; fresh sync required");
        }
        String reason = state.reason;
        if (feature == FIRST_SEEN && hasFirstSeenErrors(applied.blockNumber())) {
            reason = "First-seen history contains wallet indexing errors";
        }
        if (!state.through.equals(applied)) reason = "Index is not at the applied canonical point";
        return new WalletIndexCoverage(true, state.complete, state.from, state.through, state.identity, reason);
    }

    public AddressFirstSeen firstSeen(String address, ChainPoint applied) throws RocksDBException {
        byte[] key = addressBytes(address);
        WalletIndexCoverage coverage = coverage(FIRST_SEEN, applied);
        if (!coverage.available() || !coverage.completeFromOrigin()) {
            throw new WalletIndexUnavailableException(coverage);
        }
        byte[] value = db.get(firstSeen, key);
        if (value == null) return new AddressFirstSeen(null, coverage);
        if (value.length != Long.BYTES) throw new IllegalStateException("Invalid first-seen record; fresh sync required");
        long slot = ByteBuffer.wrap(value).getLong();
        if (slot < 0 || slot > coverage.indexedThrough().slot()) {
            throw new IllegalStateException("First-seen record is outside indexed coverage; fresh sync required");
        }
        return new AddressFirstSeen(slot, coverage);
    }

    public static byte[] addressBytes(String value) {
        try {
            // CCL dispatches to Address or ByronAddress. Preserve the full historical identity.
            byte[] bytes = AddressUtil.addressToBytes(value);
            if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty address");
            return bytes;
        } catch (AddressExcepion | RuntimeException failure) {
            throw new IllegalArgumentException("Invalid address", failure);
        }
    }

    private boolean enabled(byte feature) {
        return switch (feature) {
            case FIRST_SEEN -> firstSeenEnabled;
            case FILTERS -> filtersEnabled;
            default -> throw new IllegalArgumentException("Unknown wallet index");
        };
    }

    private static byte[] number(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
    private static byte[] undoKey(byte feature, long block) {
        return ByteBuffer.allocate(9).put(feature).putLong(block).array();
    }

    private record State(boolean complete, ChainPoint from, ChainPoint through,
                         String identity, String reason) { }
    private record Undo(byte[] previous, List<byte[]> inserted) { }

    private static byte[] encodeState(State state) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeBoolean(state.complete);
            writePoint(out, state.from);
            writePoint(out, state.through);
            out.writeUTF(state.identity);
            out.writeUTF(state.reason == null ? "" : state.reason);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static State decodeState(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IOException("Unsupported wallet index format");
            boolean complete = in.readBoolean();
            ChainPoint from = readPoint(in);
            ChainPoint through = readPoint(in);
            String identity = in.readUTF();
            String reason = in.readUTF();
            if (in.available() != 0) throw new IOException("Trailing wallet coverage bytes");
            if (identity.isBlank() || complete && !from.equals(ChainPoint.ORIGIN)
                    || from.blockNumber() > through.blockNumber() || from.slot() > through.slot()) {
                throw new IOException("Inconsistent wallet coverage");
            }
            return new State(complete, from, through, identity, reason.isEmpty() ? null : reason);
        } catch (IOException | IllegalArgumentException failure) { throw new IllegalStateException("Invalid wallet index coverage", failure); }
    }

    private static void writePoint(DataOutputStream out, ChainPoint point) throws IOException {
        out.writeLong(point.blockNumber());
        out.writeLong(point.slot());
        out.writeUTF(point.blockHash());
    }

    private static ChainPoint readPoint(DataInputStream in) throws IOException {
        return new ChainPoint(in.readLong(), in.readLong(), in.readUTF());
    }

    private static byte[] encodeUndo(byte[] previous, List<byte[]> inserted) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeInt(previous == null ? 0 : previous.length);
            if (previous != null) out.write(previous);
            out.writeInt(inserted.size());
            for (byte[] address : inserted) {
                out.writeInt(address.length);
                out.write(address);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Undo decodeUndo(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IOException("Unsupported wallet undo format");
            byte[] previous = readBytes(in);
            int count = in.readInt();
            if (count < 0 || count > bytes.length / 4) throw new IOException("Invalid wallet undo count");
            List<byte[]> inserted = new ArrayList<>(count);
            for (int i = 0; i < count; i++) inserted.add(readBytes(in));
            if (in.available() != 0) throw new IOException("Trailing wallet undo bytes");
            return new Undo(previous.length == 0 ? null : previous, inserted);
        } catch (IOException failure) { throw new IllegalStateException("Invalid wallet index undo", failure); }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > in.available()) throw new IOException("Invalid wallet record length");
        return in.readNBytes(size);
    }
}
