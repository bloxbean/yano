package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.yano.api.wallet.AddressFirstSeen;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexCoverage;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexUnavailableException;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.runtime.db.RocksDbContext;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
    private final boolean firstSeenEnabled;
    private final boolean filtersEnabled;

    public WalletIndexStore(RocksDbContext context, boolean firstSeenEnabled, boolean filtersEnabled) {
        this.db = context.db();
        this.firstSeen = context.handle(WalletIndexCf.FIRST_SEEN);
        this.filters = context.handle(WalletIndexCf.FILTERS);
        this.meta = context.handle(WalletIndexCf.META);
        this.undo = context.handle(WalletIndexCf.UNDO);
        this.genesis = context.handle(WalletIndexCf.GENESIS);
        this.firstSeenEnabled = firstSeenEnabled;
        this.filtersEnabled = filtersEnabled;
        if (enabled() && (firstSeen == null || filters == null || meta == null || undo == null)) {
            throw new IllegalStateException("Wallet index column families are missing");
        }
    }

    public boolean enabled() { return firstSeenEnabled || filtersEnabled; }
    public boolean filtersEnabled() { return filtersEnabled; }

    public record FilterRecord(WalletChainPoint point, byte[] filter) { }

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
                records.add(new FilterRecord(new WalletChainPoint(block, ByteBuffer.wrap(value).getLong(),
                        HexFormat.of().formatHex(value, 8, 40)), Arrays.copyOfRange(value, 40, value.length)));
                it.next();
            }
            it.status();
        }
        return records;
    }

    public void stageScanGenesis(WriteBatch batch, List<GenesisUtxo> outputs) throws RocksDBException {
        if (!filtersEnabled) return;
        for (GenesisUtxo output : outputs) {
            if (output.isByron()) continue; // No supported payment/stake credentials in Byron genesis.
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF(output.address());
                out.writeUTF(output.amount().toString());
                batch.put(genesis, HexFormat.of().parseHex(output.txHash()), bytes.toByteArray());
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
                            0, 0, WalletChainPoint.ORIGIN.blockHash()));
                    if (in.available() != 0) throw new IOException("Trailing scan genesis bytes");
                } catch (IOException failure) { throw new IllegalStateException("Invalid scan genesis", failure); }
            }
            it.status();
        }
        return outputs;
    }

    public void stagePruneUndo(WriteBatch batch, long blockNumber) throws RocksDBException {
        if (undo == null) return;
        if (db.get(meta, new byte[]{FIRST_SEEN}) != null) batch.delete(undo, undoKey(FIRST_SEEN, blockNumber));
        if (db.get(meta, new byte[]{FILTERS}) != null) batch.delete(undo, undoKey(FILTERS, blockNumber));
    }

    /** Only called by the owner's fresh, atomic genesis initialization. */
    public void stageGenesis(WriteBatch batch, String identity, Collection<String> addresses)
            throws RocksDBException {
        for (byte feature : new byte[]{FIRST_SEEN, FILTERS}) {
            if (!enabled(feature)) continue;
            if (db.get(meta, new byte[]{feature}) != null) {
                throw new IllegalStateException("Wallet genesis coverage already exists");
            }
            batch.put(meta, new byte[]{feature}, encodeState(new State(true,
                    WalletChainPoint.ORIGIN, WalletChainPoint.ORIGIN, identity, null)));
        }
        if (firstSeenEnabled) {
            for (String address : addresses) batch.put(firstSeen, addressBytes(address), number(0));
        }
    }

    public void stageBlock(WriteBatch batch, WalletChainPoint previous, WalletChainPoint point,
                           Collection<String> createdAddresses, byte[] filter, String failure)
            throws RocksDBException {
        stageBlock(batch, previous, point, createdAddresses, filter, failure, failure);
    }

    public void stageBlock(WriteBatch batch, WalletChainPoint previous, WalletChainPoint point,
                           Collection<String> createdAddresses, byte[] filter,
                           String firstSeenFailure, String filterFailure) throws RocksDBException {
        for (byte feature : new byte[]{FIRST_SEEN, FILTERS}) {
            if (!enabled(feature)) continue;
            byte[] key = new byte[]{feature};
            byte[] old = db.get(meta, key);
            State prior = old == null ? null : decodeState(old);
            boolean contiguous = prior != null && prior.through.equals(previous) && prior.reason == null;
            String reason = feature == FIRST_SEEN ? firstSeenFailure : filterFailure;
            if (feature == FIRST_SEEN && (!contiguous || !prior.complete)) {
                reason = "First-seen history is incomplete; enable before a fresh sync";
            }
            if (feature == FILTERS && filter == null && reason == null) reason = "Filter extraction failed";
            State next = new State(contiguous && prior.complete, contiguous ? prior.from : point,
                    point, prior == null ? "uninitialized" : prior.identity, reason);
            List<byte[]> inserted = new ArrayList<>();
            if (feature == FIRST_SEEN && reason == null) {
                TreeSet<byte[]> distinct = new TreeSet<>(Arrays::compareUnsigned);
                for (String address : createdAddresses) distinct.add(addressBytes(address));
                for (byte[] address : distinct) {
                    if (db.get(firstSeen, address) == null) {
                        batch.put(firstSeen, address, number(point.slot()));
                        inserted.add(address);
                    }
                }
            }
            if (feature == FILTERS && reason == null) {
                byte[] hash = HexFormat.of().parseHex(point.blockHash());
                batch.put(filters, number(point.blockNumber()), ByteBuffer.allocate(40 + filter.length)
                        .putLong(point.slot()).put(hash).put(filter).array());
            }
            batch.put(undo, undoKey(feature, point.blockNumber()), encodeUndo(old, inserted));
            batch.put(meta, key, encodeState(next));
        }
    }

    /** Fail closed even when the previous derived metadata cannot be decoded. */
    public void stageUnavailable(WriteBatch batch, WalletChainPoint point, String reason) throws RocksDBException {
        for (byte feature : new byte[]{FIRST_SEEN, FILTERS}) {
            if (!enabled(feature)) continue;
            byte[] key = new byte[]{feature};
            byte[] old = db.get(meta, key);
            State prior = null;
            if (old != null) {
                try { prior = decodeState(old); }
                catch (RuntimeException invalidMetadata) { old = null; }
            }
            State unavailable = new State(false, prior == null ? point : prior.from, point,
                    prior == null ? "unknown" : prior.identity, reason);
            batch.put(undo, undoKey(feature, point.blockNumber()), encodeUndo(old, List.of()));
            batch.put(meta, key, encodeState(unavailable));
        }
    }

    /** Stage all inverse operations, including indexes temporarily disabled in config. */
    public void stageRollback(WriteBatch batch, WalletChainPoint target) throws RocksDBException {
        if (meta == null) return;
        for (byte feature : new byte[]{FIRST_SEEN, FILTERS}) {
            try {
                stageFeatureRollback(batch, target, feature);
            } catch (IllegalStateException invalidDerivedRecord) {
                // Malformed derived records must not prevent the canonical UTxO rollback.
                // Partial staged cleanup is safe because this feature is now unavailable.
                batch.put(meta, new byte[]{feature}, encodeState(new State(false, target, target,
                        "unknown", "Wallet rollback metadata or undo invalid; fresh sync required")));
            }
        }
    }

    private void stageFeatureRollback(WriteBatch batch, WalletChainPoint target, byte feature)
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
                for (byte[] address : entry.inserted) batch.delete(firstSeen, address);
                if (feature == FILTERS) batch.delete(filters, number(block));
                stateBytes = entry.previous;
                batch.delete(undo, it.key());
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
        if (restored == null) batch.delete(meta, new byte[]{feature});
        else batch.put(meta, new byte[]{feature}, encodeState(restored));
    }

    public WalletIndexCoverage coverage(byte feature, WalletChainPoint applied) throws RocksDBException {
        if (!enabled(feature)) return new WalletIndexCoverage(false, false, null, null, null, "Index disabled");
        byte[] encoded = db.get(meta, new byte[]{feature});
        if (encoded == null) return new WalletIndexCoverage(true, false, null, null, null,
                "Index missing; enable before a fresh sync");
        State state = decodeState(encoded);
        String reason = state.reason;
        if (!state.through.equals(applied)) reason = "Index is not at the applied canonical point";
        return new WalletIndexCoverage(true, state.complete, state.from, state.through, state.identity, reason);
    }

    public AddressFirstSeen firstSeen(String address, WalletChainPoint applied) throws RocksDBException {
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
        return WalletAddresses.decode(value);
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

    private record State(boolean complete, WalletChainPoint from, WalletChainPoint through,
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
            WalletChainPoint from = readPoint(in);
            WalletChainPoint through = readPoint(in);
            String identity = in.readUTF();
            String reason = in.readUTF();
            if (in.available() != 0) throw new IOException("Trailing wallet coverage bytes");
            if (identity.isBlank() || complete && !from.equals(WalletChainPoint.ORIGIN)
                    || from.blockNumber() > through.blockNumber() || from.slot() > through.slot()) {
                throw new IOException("Inconsistent wallet coverage");
            }
            return new State(complete, from, through, identity, reason.isEmpty() ? null : reason);
        } catch (IOException | IllegalArgumentException failure) { throw new IllegalStateException("Invalid wallet index coverage", failure); }
    }

    private static void writePoint(DataOutputStream out, WalletChainPoint point) throws IOException {
        out.writeLong(point.blockNumber());
        out.writeLong(point.slot());
        out.writeUTF(point.blockHash());
    }

    private static WalletChainPoint readPoint(DataInputStream in) throws IOException {
        return new WalletChainPoint(in.readLong(), in.readLong(), in.readUTF());
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
