package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxo;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialExtractor;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCfNames;
import com.bloxbean.cardano.yano.runtime.db.UtxoCfNames;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline verifier and repairer for a missing coordinate-bound pointer UTXO
 * index marker.
 *
 * <p>Opening RocksDB read/write acquires its exclusive process lock. The marker
 * is written synchronously only after a snapshot scan proves exact agreement
 * between every live UTXO and the pointer index, and after the durable
 * coordinates are re-read outside that snapshot.</p>
 */
public final class PointerIndexRepair {
    private static final byte[] CHAIN_TIP = bytes("tip");
    private static final byte[] UTXO_BLOCK = bytes("meta.last_applied_block");
    private static final byte[] UTXO_SLOT = bytes("meta.last_applied_slot");
    private static final byte[] UTXO_HASH = bytes("meta.last_applied_hash");
    private static final byte[] ACCOUNT_BLOCK = bytes("meta.last_block");
    private static final byte[] ACCOUNT_SLOT = bytes("meta.last_applied_slot");
    private static final byte[] ACCOUNT_POINTER_COMPLETE =
            bytes("meta.pointer.index.from-genesis");
    private static final byte[] ACCOUNT_POINTER_CLEANED =
            bytes("meta.pointer.index.cleaned-through");

    private PointerIndexRepair() {
    }

    public static RepairResult repair(Path requestedDatabase) throws Exception {
        return repair(requestedDatabase, () -> { });
    }

    static RepairResult repair(Path requestedDatabase, Runnable afterScan) throws Exception {
        Path database = requireDatabase(requestedDatabase);
        Objects.requireNonNull(afterScan, "afterScan");
        RocksDB.loadLibrary();

        List<byte[]> names;
        try (Options options = new Options()) {
            names = RocksDB.listColumnFamilies(options, database.toString());
        }
        List<ColumnFamilyDescriptor> descriptors = names.stream()
                .map(name -> new ColumnFamilyDescriptor(
                        name, new ColumnFamilyOptions()))
                .toList();
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try (DBOptions options = new DBOptions()
                .setCreateIfMissing(false)
                .setCreateMissingColumnFamilies(false);
             RocksDB db = RocksDB.open(
                     options, database.toString(), descriptors, handles)) {
            Map<String, ColumnFamilyHandle> byName = handlesByName(names, handles);
            ColumnFamilyHandle metadata = requireHandle(byName, "metadata");
            ColumnFamilyHandle unspent = requireHandle(
                    byName, UtxoCfNames.UTXO_UNSPENT);
            ColumnFamilyHandle pointer = requireHandle(
                    byName, UtxoCfNames.UTXO_POINTER);
            ColumnFamilyHandle utxoMeta = requireHandle(
                    byName, UtxoCfNames.UTXO_META);
            ColumnFamilyHandle accountState = requireHandle(
                    byName, AccountStateCfNames.ACCT_STATE);

            long startedNanos = System.nanoTime();
            CoordinateProof proof;
            ScanCounts counts;
            byte[] existingMarker;
            Snapshot snapshot = db.getSnapshot();
            try (ReadOptions readOptions = new ReadOptions()
                    .setSnapshot(snapshot)
                    .setFillCache(false)) {
                proof = requireCoordinateProof(
                        db, readOptions, metadata, utxoMeta, accountState);
                existingMarker = db.get(
                        utxoMeta, readOptions, PointerIndexMarker.KEY);
                if (existingMarker != null) {
                    PointerIndexMarker marker = PointerIndexMarker.decode(existingMarker);
                    if (marker == null || !marker.isUsableAt(proof.coordinate())) {
                        throw new IllegalStateException(
                                "pointer index marker exists but is malformed or unusable; "
                                        + "refusing to replace it automatically");
                    }
                }
                counts = verifyIndex(db, readOptions, unspent, pointer);
                afterScan.run();
            } finally {
                db.releaseSnapshot(snapshot);
            }

            try (ReadOptions currentRead = new ReadOptions().setFillCache(false)) {
                CoordinateProof current = requireCoordinateProof(
                        db, currentRead, metadata, utxoMeta, accountState);
                if (!proof.sameAs(current)) {
                    throw new IllegalStateException(
                            "chainstate coordinate changed during pointer index verification");
                }
                byte[] currentMarker = db.get(
                        utxoMeta, currentRead, PointerIndexMarker.KEY);
                if (!Arrays.equals(existingMarker, currentMarker)) {
                    throw new IllegalStateException(
                            "pointer index marker changed during verification");
                }
            }

            boolean repaired = existingMarker == null;
            if (repaired) {
                try (WriteOptions writeOptions = new WriteOptions().setSync(true)) {
                    db.put(utxoMeta, writeOptions, PointerIndexMarker.KEY,
                            PointerIndexMarker.encode(PointerIndexMarker.at(
                                    proof.coordinate())));
                }
            }
            return new RepairResult(
                    proof.coordinate(), counts.unspentRows(), counts.pointerRows(),
                    Duration.ofNanos(System.nanoTime() - startedNanos), repaired);
        } finally {
            handles.forEach(ColumnFamilyHandle::close);
            descriptors.forEach(descriptor -> descriptor.getOptions().close());
        }
    }

    private static ScanCounts verifyIndex(
            RocksDB db,
            ReadOptions readOptions,
            ColumnFamilyHandle unspent,
            ColumnFamilyHandle pointer) throws Exception {
        long unspentRows = 0;
        long pointerRows = 0;
        try (RocksIterator unspentIterator = db.newIterator(unspent, readOptions);
             RocksIterator pointerIterator = db.newIterator(pointer, readOptions)) {
            unspentIterator.seekToFirst();
            pointerIterator.seekToFirst();
            while (unspentIterator.isValid()) {
                byte[] outpoint = unspentIterator.key();
                requireOutpoint(outpoint, "UTXO");
                if (pointerIterator.isValid()
                        && Arrays.compareUnsigned(pointerIterator.key(), outpoint) < 0) {
                    throw new IllegalStateException(
                            "orphan pointer index row for outpoint "
                                    + toHex(pointerIterator.key()));
                }

                UtxoCborCodec.StoredUtxo stored;
                try {
                    stored = UtxoCborCodec.decodeUtxoRecord(
                            unspentIterator.value());
                } catch (RuntimeException malformed) {
                    throw new IllegalStateException(
                            "malformed live UTXO " + toHex(outpoint), malformed);
                }
                PointerUtxo expected = expectedPointer(stored);
                boolean matchingKey = pointerIterator.isValid()
                        && Arrays.equals(pointerIterator.key(), outpoint);
                if (expected == null) {
                    if (matchingKey) {
                        throw new IllegalStateException(
                                "non-pointer UTXO has a pointer index row: "
                                        + toHex(outpoint));
                    }
                } else {
                    if (!matchingKey) {
                        throw new IllegalStateException(
                                "pointer-address UTXO is missing its index row: "
                                        + toHex(outpoint));
                    }
                    requireOutpoint(pointerIterator.key(), "pointer index");
                    PointerUtxo actual;
                    try {
                        actual = PointerUtxoCodec.decode(pointerIterator.value());
                    } catch (RuntimeException malformed) {
                        throw new IllegalStateException(
                                "malformed pointer index row for " + toHex(outpoint),
                                malformed);
                    }
                    if (!expected.equals(actual)) {
                        throw new IllegalStateException(
                                "pointer index row does not match its live UTXO: "
                                        + toHex(outpoint));
                    }
                    pointerRows++;
                    pointerIterator.next();
                }
                unspentRows++;
                unspentIterator.next();
            }
            if (pointerIterator.isValid()) {
                throw new IllegalStateException(
                        "orphan pointer index row for outpoint "
                                + toHex(pointerIterator.key()));
            }
            unspentIterator.status();
            pointerIterator.status();
        }
        return new ScanCounts(unspentRows, pointerRows);
    }

    private static PointerUtxo expectedPointer(UtxoCborCodec.StoredUtxo stored) {
        Address address = StakeCredentialExtractor.parseAddressOrNull(stored.address);
        if (address == null || address.getAddressType() != AddressType.Ptr) {
            return null;
        }
        return new PointerUtxo(
                stored.slot, stored.lovelace,
                StakeCredentialExtractor.extractPointer(address));
    }

    private static CoordinateProof requireCoordinateProof(
            RocksDB db,
            ReadOptions readOptions,
            ColumnFamilyHandle metadata,
            ColumnFamilyHandle utxoMeta,
            ColumnFamilyHandle accountState) throws Exception {
        CanonicalBlockReference chain = readChainTip(
                db.get(metadata, readOptions, CHAIN_TIP));
        CanonicalBlockReference utxo = new CanonicalBlockReference(
                readLong(db.get(utxoMeta, readOptions, UTXO_BLOCK), "UTXO block"),
                readLong(db.get(utxoMeta, readOptions, UTXO_SLOT), "UTXO slot"),
                readHash(db.get(utxoMeta, readOptions, UTXO_HASH), "UTXO hash"));
        long accountBlock = readLong(
                db.get(accountState, readOptions, ACCOUNT_BLOCK), "account block");
        long accountSlot = readLong(
                db.get(accountState, readOptions, ACCOUNT_SLOT), "account slot");
        if (!sameCoordinate(chain, utxo)) {
            throw new IllegalStateException(
                    "chain tip and UTXO coordinate do not agree");
        }
        if (accountBlock != chain.blockNumber() || accountSlot != chain.slot()) {
            throw new IllegalStateException(
                    "account-state coordinate does not agree with chain tip");
        }
        if (db.get(accountState, readOptions, ACCOUNT_POINTER_COMPLETE) == null) {
            throw new IllegalStateException(
                    "account-state pointer index is not proven complete from genesis");
        }
        if (db.get(accountState, readOptions, ACCOUNT_POINTER_CLEANED) != null) {
            throw new IllegalStateException(
                    "account-state pointer history has been cleaned and cannot prove recovery");
        }
        return new CoordinateProof(chain, accountBlock, accountSlot);
    }

    private static CanonicalBlockReference readChainTip(byte[] value) {
        if (value == null || value.length != Long.BYTES * 2 + 32) {
            throw new IllegalStateException("chain tip is missing or malformed");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        long slot = buffer.getLong();
        long block = buffer.getLong();
        byte[] hash = new byte[32];
        buffer.get(hash);
        return new CanonicalBlockReference(block, slot, hash);
    }

    private static long readLong(byte[] value, String label) {
        if (value == null || value.length != Long.BYTES) {
            throw new IllegalStateException(label + " metadata is missing or malformed");
        }
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static byte[] readHash(byte[] value, String label) {
        if (value == null || value.length != 32) {
            throw new IllegalStateException(label + " metadata is missing or malformed");
        }
        return Arrays.copyOf(value, value.length);
    }

    private static boolean sameCoordinate(
            CanonicalBlockReference left, CanonicalBlockReference right) {
        return left.blockNumber() == right.blockNumber()
                && left.slot() == right.slot()
                && Arrays.equals(left.blockHash(), right.blockHash());
    }

    private static Path requireDatabase(Path requestedDatabase) {
        Path database = Objects.requireNonNull(requestedDatabase, "database")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(database)
                || !Files.isRegularFile(database.resolve("CURRENT"))
                || database.getParent() == null
                || database.getNameCount() < 2) {
            throw new IllegalArgumentException(
                    "database must be an existing, explicit RocksDB directory");
        }
        return database;
    }

    private static Map<String, ColumnFamilyHandle> handlesByName(
            List<byte[]> names, List<ColumnFamilyHandle> handles) {
        Map<String, ColumnFamilyHandle> result = new HashMap<>();
        for (int index = 0; index < names.size(); index++) {
            result.put(new String(names.get(index), StandardCharsets.UTF_8),
                    handles.get(index));
        }
        return result;
    }

    private static ColumnFamilyHandle requireHandle(
            Map<String, ColumnFamilyHandle> handles, String name) {
        ColumnFamilyHandle handle = handles.get(name);
        if (handle == null) {
            throw new IllegalStateException(
                    "required RocksDB column family is missing: " + name);
        }
        return handle;
    }

    private static void requireOutpoint(byte[] key, String source) {
        if (key == null || key.length != 34) {
            throw new IllegalStateException(
                    source + " outpoint key must be exactly 34 bytes");
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) {
            result.append(Character.forDigit((current >>> 4) & 0x0F, 16));
            result.append(Character.forDigit(current & 0x0F, 16));
        }
        return result.toString();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record ScanCounts(long unspentRows, long pointerRows) {
    }

    private record CoordinateProof(
            CanonicalBlockReference coordinate,
            long accountBlock,
            long accountSlot) {
        boolean sameAs(CoordinateProof other) {
            return other != null
                    && accountBlock == other.accountBlock
                    && accountSlot == other.accountSlot
                    && sameCoordinate(coordinate, other.coordinate);
        }
    }

    public record RepairResult(
            CanonicalBlockReference coordinate,
            long unspentRows,
            long pointerRows,
            Duration elapsed,
            boolean repaired) {
    }
}
