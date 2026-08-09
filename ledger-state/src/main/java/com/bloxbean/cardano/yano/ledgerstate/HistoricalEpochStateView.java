package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/** Consistent, close-scoped read snapshot for ADR-028 historical ledger datasets. */
public final class HistoricalEpochStateView implements AutoCloseable {
    private final RocksDB db;
    private final ColumnFamilyHandle stakeSnapshots;
    private final Snapshot snapshot;
    private final ReadOptions reads;
    private boolean closed;

    HistoricalEpochStateView(RocksDB db, ColumnFamilyHandle stakeSnapshots) {
        this.db = Objects.requireNonNull(db, "db");
        this.stakeSnapshots = Objects.requireNonNull(stakeSnapshots, "stakeSnapshots");
        this.snapshot = db.getSnapshot();
        this.reads = new ReadOptions().setSnapshot(snapshot);
    }

    public boolean hasStakeSnapshot(int epoch) {
        requireOpen();
        byte[] prefix = epochPrefix(epoch);
        try (RocksIterator iterator = db.newIterator(stakeSnapshots, reads)) {
            iterator.seek(prefix);
            return iterator.isValid() && keyEpoch(iterator.key()) == epoch;
        }
    }

    /** RocksDB key order is the canonical `(credType, credHash)` byte order. */
    public void forEachStakeEntry(int epoch, StakeConsumer consumer) {
        requireOpen();
        Objects.requireNonNull(consumer, "consumer");
        byte[] prefix = epochPrefix(epoch);
        try (RocksIterator iterator = db.newIterator(stakeSnapshots, reads)) {
            iterator.seek(prefix);
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length != 33 || keyEpoch(key) != epoch) break;
                var value = AccountStateCborCodec.decodeEpochDelegSnapshot(iterator.value());
                byte[] credentialHash = Arrays.copyOfRange(key, 5, 33);
                byte[] poolHash = HexUtil.decodeHexString(value.poolHash());
                if (poolHash.length != 28) {
                    throw new IllegalStateException("epoch stake pool hash must contain 28 bytes");
                }
                consumer.accept(key[4] & 0xFF, credentialHash, value.amount(), poolHash);
                iterator.next();
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            reads.close();
            db.releaseSnapshot(snapshot);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("historical epoch view is closed");
    }

    private static byte[] epochPrefix(int epoch) {
        if (epoch < 0) throw new IllegalArgumentException("epoch cannot be negative");
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();
    }

    private static int keyEpoch(byte[] key) {
        return key.length >= 4
                ? ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
                : -1;
    }

    @FunctionalInterface
    public interface StakeConsumer {
        void accept(int credentialType, byte[] credentialHash,
                    BigInteger coin, byte[] poolHash);
    }
}
