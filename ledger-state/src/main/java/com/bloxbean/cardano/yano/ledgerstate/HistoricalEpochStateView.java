package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceActionType;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatus;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatusReason;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceCborCodec;
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
    private final ColumnFamilyHandle state;
    private final Snapshot snapshot;
    private final ReadOptions reads;
    private boolean closed;

    HistoricalEpochStateView(RocksDB db, ColumnFamilyHandle stakeSnapshots,
                             ColumnFamilyHandle state) {
        this.db = Objects.requireNonNull(db, "db");
        this.stakeSnapshots = Objects.requireNonNull(stakeSnapshots, "stakeSnapshots");
        this.state = Objects.requireNonNull(state, "state");
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

    public boolean hasProposalStatusSnapshot(int epoch) {
        return hasMarker((byte) 0x72, epoch);
    }

    public boolean hasDRepDistributionSnapshot(int epoch) {
        return hasMarker((byte) 0x73, epoch);
    }

    /** Canonical RocksDB order is `(transactionId, governanceActionIndex)`. */
    public void forEachProposalStatus(int epoch, ProposalStatusConsumer consumer) {
        requireOpen();
        Objects.requireNonNull(consumer, "consumer");
        byte[] prefix = stateEpochPrefix((byte) 0x71, epoch);
        try (RocksIterator iterator = db.newIterator(state, reads)) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                byte[] key = iterator.key();
                if (key.length != 39) throw new IllegalStateException("invalid proposal lifecycle key");
                var value = GovernanceCborCodec.decodeProposalLifecycle(iterator.value());
                consumer.accept(Arrays.copyOfRange(key, 5, 37),
                        ((key[37] & 0xFF) << 8) | (key[38] & 0xFF),
                        value.actionType(), value.status(), value.reason(),
                        value.proposedEpoch(), value.expiresAfterEpoch());
                iterator.next();
            }
        }
    }

    /** Canonical RocksDB order is `(drepType, drepHash)`. */
    public void forEachDRepDistributionEntry(int epoch, DRepDistributionConsumer consumer) {
        requireOpen();
        Objects.requireNonNull(consumer, "consumer");
        byte[] prefix = stateEpochPrefix((byte) 0x66, epoch);
        try (RocksIterator iterator = db.newIterator(state, reads)) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                byte[] key = iterator.key();
                if (key.length != 34) throw new IllegalStateException("invalid DRep distribution key");
                consumer.accept(key[5] & 0xFF, Arrays.copyOfRange(key, 6, 34),
                        GovernanceCborCodec.decodeDRepDistStake(iterator.value()));
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

    private boolean hasMarker(byte markerPrefix, int epoch) {
        requireOpen();
        try {
            return db.get(state, reads, stateEpochPrefix(markerPrefix, epoch)) != null;
        } catch (org.rocksdb.RocksDBException e) {
            throw new IllegalStateException("historical epoch marker read failed", e);
        }
    }

    private static byte[] stateEpochPrefix(byte prefix, int epoch) {
        if (epoch < 0) throw new IllegalArgumentException("epoch cannot be negative");
        return ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(prefix).putInt(epoch).array();
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
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

    @FunctionalInterface
    public interface ProposalStatusConsumer {
        void accept(byte[] transactionId, int governanceActionIndex,
                    GovernanceActionType actionType, GovernanceProposalStatus status,
                    GovernanceProposalStatusReason reason, long proposedEpoch,
                    long expiresAfterEpoch);
    }

    @FunctionalInterface
    public interface DRepDistributionConsumer {
        void accept(int drepType, byte[] drepHash, BigInteger coin);
    }
}
