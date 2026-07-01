package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.OperationalCert;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.account.OpCertCounterState;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Rollback-safe op-cert counter mutations are journaled through
 * DefaultAccountStateStore's per-block delta mechanism.
 */
final class OpCertCounterTracker {
    private RocksDB db;
    private ColumnFamilyHandle cfState;

    OpCertCounterTracker(RocksDB db, ColumnFamilyHandle cfState) {
        this.db = db;
        this.cfState = cfState;
    }

    void reinitialize(RocksDB db, ColumnFamilyHandle cfState) {
        this.db = db;
        this.cfState = cfState;
    }

    Optional<OpCertCounterState> get(String issuerKeyHash) {
        String normalized = normalizeIssuerHash(issuerKeyHash);
        if (normalized == null) {
            return Optional.empty();
        }
        try {
            byte[] value = db.get(cfState, opCertCounterKey(normalized));
            if (value == null) {
                return Optional.empty();
            }
            AccountStateCborCodec.OpCertCounterData data =
                    AccountStateCborCodec.decodeOpCertCounter(value);
            return Optional.of(new OpCertCounterState(
                    normalized,
                    data.counter(),
                    data.lastUpdatedSlot(),
                    data.lastUpdatedBlockNumber(),
                    data.lastUpdatedBlockHash()));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to read op-cert counter for issuer " + normalized, e);
        }
    }

    void applyBlock(Block block,
                    long slot,
                    long blockNumber,
                    String blockHash,
                    WriteBatch batch,
                    List<DefaultAccountStateStore.DeltaOp> deltaOps,
                    DefaultAccountStateStore store) throws RocksDBException {
        Optional<CounterUpdate> update = counterUpdate(block, slot, blockNumber, blockHash);
        if (update.isEmpty()) {
            return;
        }

        CounterUpdate counterUpdate = update.orElseThrow();
        store.putStateWithDelta(counterUpdate.key(), counterUpdate.value(), batch, deltaOps);
    }

    private Optional<CounterUpdate> counterUpdate(Block block, long slot, long blockNumber, String blockHash) {
        HeaderBody headerBody = block != null && block.getHeader() != null
                ? block.getHeader().getHeaderBody()
                : null;
        if (headerBody == null) {
            return Optional.empty();
        }
        String issuerVkey = headerBody.getIssuerVkey();
        OperationalCert opCert = headerBody.getOperationalCert();
        if (issuerVkey == null || issuerVkey.isBlank()
                || opCert == null || opCert.getSequenceNumber() == null) {
            return Optional.empty();
        }

        String issuerKeyHash = issuerKeyHashFromVkey(issuerVkey);
        long counter = opCert.getSequenceNumber().longValue();
        byte[] key = opCertCounterKey(issuerKeyHash);
        byte[] value = AccountStateCborCodec.encodeOpCertCounter(
                new AccountStateCborCodec.OpCertCounterData(
                        counter,
                        slot,
                        blockNumber,
                        normalizeNullable(blockHash)));
        return Optional.of(new CounterUpdate(issuerKeyHash, key, value));
    }

    private record CounterUpdate(String issuerKeyHash, byte[] key, byte[] value) {}

    static String issuerKeyHashFromVkey(String issuerVkey) {
        byte[] vkeyBytes = HexUtil.decodeHexString(normalizeRequired(issuerVkey, "issuerVkey"));
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(vkeyBytes));
    }

    static byte[] opCertCounterKey(String issuerKeyHash) {
        byte[] hash = HexUtil.decodeHexString(normalizeRequired(issuerKeyHash, "issuerKeyHash"));
        byte[] key = new byte[hash.length + 1];
        key[0] = DefaultAccountStateStore.PREFIX_OPCERT_COUNTER;
        System.arraycopy(hash, 0, key, 1, hash.length);
        return key;
    }

    static String normalizeIssuerHash(String issuerKeyHash) {
        String normalized = normalizeNullable(issuerKeyHash);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private static String normalizeRequired(String value, String name) {
        String normalized = normalizeIssuerHash(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
