package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Resolves pointer addresses to stake credentials.
 * <p>
 * Pointer addresses encode (slot, txIndex, certIndex) instead of a credential hash.
 * This resolver reads from RocksDB where pointer → credential mappings are persisted
 * during stake registration processing (PREFIX_POINTER_ADDR entries).
 * <p>
 * Conway era removes pointer address support — only matters for pre-Conway replay.
 * Less than 1% of mainnet UTXOs use pointer addresses — negligible performance impact.
 */
public class PointerAddressResolver {
    private static final Logger log = LoggerFactory.getLogger(PointerAddressResolver.class);

    /**
     * Resolved stake credential from a pointer address.
     */
    public record StakeCredential(int credType, String credHash) {}

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;

    public PointerAddressResolver(RocksDB db, ColumnFamilyHandle cfState) {
        this.db = db;
        this.cfState = cfState;
    }

    /**
     * Register a stake credential at a certificate pointer location.
     * Called when processing STAKE_REGISTRATION and REG_CERT certificates.
     * Note: the actual RocksDB write is done via WriteBatch in registerStake();
     * this method is kept for API compatibility but is a no-op.
     */
    public void registerPointer(long slot, int txIndex, int certIndex,
                                int credType, String credHash) {
        // No-op: persistence is handled by DefaultAccountStateStore.registerStake()
        // via WriteBatch (pointerAddrKey/pointerAddrValue).
    }

    /**
     * Resolve a pointer address to a stake credential by reading from RocksDB.
     *
     * @param slot      the slot from the pointer address
     * @param txIndex   the transaction index from the pointer address
     * @param certIndex the certificate index from the pointer address
     * @return the resolved credential, or null if not found
     */
    public StakeCredential resolve(long slot, int txIndex, int certIndex) {
        try {
            byte[] key = new byte[1 + 8 + 4 + 4];
            key[0] = DefaultAccountStateStore.PREFIX_POINTER_ADDR;
            ByteBuffer.wrap(key, 1, 8).order(ByteOrder.BIG_ENDIAN).putLong(slot);
            ByteBuffer.wrap(key, 9, 4).order(ByteOrder.BIG_ENDIAN).putInt(txIndex);
            ByteBuffer.wrap(key, 13, 4).order(ByteOrder.BIG_ENDIAN).putInt(certIndex);

            byte[] val = db.get(cfState, key);
            if (val == null || val.length < 2) {
                if (log.isDebugEnabled())
                    log.debug("Pointer not found in RocksDB: slot={}, txIdx={}, certIdx={}", slot, txIndex, certIndex);
                return null;
            }
            if (log.isDebugEnabled())
                log.debug("Pointer resolved from RocksDB: slot={}, txIdx={}, certIdx={}", slot, txIndex, certIndex);

            int credType = val[0] & 0xFF;
            byte[] hashBytes = new byte[val.length - 1];
            System.arraycopy(val, 1, hashBytes, 0, hashBytes.length);
            return new StakeCredential(credType, HexUtil.encodeHexString(hashBytes));
        } catch (Exception e) {
            log.warn("Pointer resolve failed for ({}, {}, {}): {}", slot, txIndex, certIndex, e.getMessage());
            return null;
        }
    }
}
