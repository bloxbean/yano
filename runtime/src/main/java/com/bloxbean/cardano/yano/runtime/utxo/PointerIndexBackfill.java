package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.utxo.PointerAddressId;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxo;
import com.bloxbean.cardano.yano.api.utxo.StakeBalanceConsistencyException;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialExtractor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** TODO(#97): remove after the pre-Conway clone trial. */
final class PointerIndexBackfill {
    private static final int BATCH_OPERATIONS = 10_000;

    @FunctionalInterface
    interface CoordinateReader {
        CanonicalBlockReference read(ReadOptions readOptions) throws RocksDBException;
    }

    private final RocksDB db;
    private final ColumnFamilyHandle unspent;
    private final ColumnFamilyHandle pointer;
    private final ColumnFamilyHandle meta;
    private final CoordinateReader coordinateReader;
    private final Logger log;

    PointerIndexBackfill(
            RocksDB db,
            ColumnFamilyHandle unspent,
            ColumnFamilyHandle pointer,
            ColumnFamilyHandle meta,
            CoordinateReader coordinateReader,
            Logger log) {
        this.db = db;
        this.unspent = unspent;
        this.pointer = pointer;
        this.meta = meta;
        this.coordinateReader = coordinateReader;
        this.log = log;
    }

    void run(CanonicalBlockReference expectedCoordinate,
             long maxCreationSlot,
             Consumer<PointerUtxo> observer) {
        long started = System.currentTimeMillis();
        long scanned = 0;
        long indexed = 0;

        log.info("Pointer UTXO index backfill started: block={}, slot={}, maxCreationSlot={}",
                expectedCoordinate.blockNumber(), expectedCoordinate.slot(), maxCreationSlot);

        // Marker removal precedes every destructive/rebuild write. A crash
        // therefore leaves an unavailable index, never a partial ready one.
        try {
            db.delete(meta, PointerIndexMarker.KEY);
            clearPointerRows();
        } catch (RocksDBException e) {
            throw new StakeBalanceConsistencyException(
                    "Failed to reset partial pointer index backfill", e);
        }

        Snapshot snapshot = db.getSnapshot();
        try (ReadOptions readOptions = new ReadOptions()
                .setSnapshot(snapshot).setFillCache(false);
             RocksIterator iterator = db.newIterator(unspent, readOptions);
             WriteOptions writeOptions = new WriteOptions();
             WriteBatch batch = new WriteBatch()) {
            requireSameCoordinate(expectedCoordinate, coordinateReader.read(readOptions));
            int staged = 0;
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                scanned++;
                UtxoCborCodec.StoredUtxo stored = UtxoCborCodec.decodeUtxoRecord(iterator.value());
                Address address = StakeCredentialExtractor.parseAddressOrNull(stored.address);
                if (address == null || address.getAddressType() != AddressType.Ptr) continue;
                PointerAddressId addressPointer = StakeCredentialExtractor.extractPointer(address);
                PointerUtxo pointerUtxo = new PointerUtxo(
                        stored.slot, stored.lovelace, addressPointer);
                batch.put(pointer, Arrays.copyOf(iterator.key(), iterator.key().length),
                        PointerUtxoCodec.encode(pointerUtxo));
                // Preparation refuses a coordinate after the cutoff, so every
                // current row is eligible. Retain the creation-slot guard as a
                // defensive mirror of the index cursor contract.
                if (stored.slot <= maxCreationSlot) observer.accept(pointerUtxo);
                indexed++;
                staged++;
                if (staged >= BATCH_OPERATIONS) {
                    db.write(writeOptions, batch);
                    batch.clear();
                    staged = 0;
                }
            }
            iterator.status();
            if (staged > 0) db.write(writeOptions, batch);
        } catch (Exception e) {
            throw new StakeBalanceConsistencyException(
                    "Pointer index backfill failed before ready marker", e);
        } finally {
            db.releaseSnapshot(snapshot);
        }

        try (ReadOptions readOptions = new ReadOptions().setFillCache(false);
             WriteOptions writeOptions = new WriteOptions();
             WriteBatch batch = new WriteBatch()) {
            CanonicalBlockReference actual = coordinateReader.read(readOptions);
            requireSameCoordinate(expectedCoordinate, actual);
            batch.put(meta, PointerIndexMarker.KEY,
                    PointerIndexMarker.encode(PointerIndexMarker.at(actual)));
            db.write(writeOptions, batch);
        } catch (Exception e) {
            throw new StakeBalanceConsistencyException(
                    "Failed to publish pointer index ready marker", e);
        }

        log.info("Pointer UTXO index backfill complete: scanned={}, indexed={}, "
                        + "block={}, slot={}, elapsedMs={}",
                scanned, indexed, expectedCoordinate.blockNumber(),
                expectedCoordinate.slot(), System.currentTimeMillis() - started);
    }

    private void clearPointerRows() throws RocksDBException {
        try (RocksIterator iterator = db.newIterator(pointer);
             WriteOptions writeOptions = new WriteOptions()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                List<byte[]> keys = new ArrayList<>(BATCH_OPERATIONS);
                while (iterator.isValid() && keys.size() < BATCH_OPERATIONS) {
                    keys.add(Arrays.copyOf(iterator.key(), iterator.key().length));
                    iterator.next();
                }
                try (WriteBatch batch = new WriteBatch()) {
                    for (byte[] key : keys) batch.delete(pointer, key);
                    db.write(writeOptions, batch);
                }
            }
            iterator.status();
        }
    }

    private static void requireSameCoordinate(
            CanonicalBlockReference expected, CanonicalBlockReference actual) {
        if (expected.blockNumber() != actual.blockNumber()
                || expected.slot() != actual.slot()
                || !Arrays.equals(expected.blockHash(), actual.blockHash())) {
            throw new StakeBalanceConsistencyException(
                    "Pointer backfill coordinate mismatch: expected block="
                            + expected.blockNumber() + " slot=" + expected.slot()
                            + ", actual block=" + actual.blockNumber()
                            + " slot=" + actual.slot());
        }
    }
}
