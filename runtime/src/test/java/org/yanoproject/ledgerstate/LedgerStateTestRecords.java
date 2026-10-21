package org.yanoproject.ledgerstate;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.math.BigInteger;
import java.util.Set;

/**
 * Test-only raw writer for account-state records in the store's own encoding (same package as the
 * package-private codecs), for runtime tests that need to control exactly when a write lands.
 */
public final class LedgerStateTestRecords {

    private LedgerStateTestRecords() {
    }

    public static void putStakeAccount(RocksDB db, ColumnFamilyHandle cfState, int credType, String hash,
                                       BigInteger reward, BigInteger deposit) throws RocksDBException {
        db.put(cfState, DefaultAccountStateStore.accountKey(credType, hash),
                AccountStateCborCodec.encodeStakeAccount(reward, deposit));
    }

    public static void putCorrupt(RocksDB db, ColumnFamilyHandle cfState, int credType, String hash)
            throws RocksDBException {
        db.put(cfState, DefaultAccountStateStore.accountKey(credType, hash), new byte[]{(byte) 0xff, 0x01});
    }

    public static void putTreasury(RocksDB db, ColumnFamilyHandle cfState, int epoch, BigInteger treasury)
            throws RocksDBException {
        var pot = new AccountStateCborCodec.AdaPot(treasury, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        db.put(cfState, DefaultAccountStateStore.adaPotKey(epoch), AccountStateCborCodec.encodeAdaPot(pot));
    }

    /** A live pool record (0x10) and one history row keyed {@code historyEpoch}, without a registration cert. */
    public static void putPool(RocksDB db, ColumnFamilyHandle cfState, String poolHash, String vrf, int historyEpoch)
            throws RocksDBException {
        var data = new AccountStateCborCodec.PoolRegistrationData(BigInteger.TEN, BigInteger.ZERO, BigInteger.ONE,
                BigInteger.ONE, BigInteger.ONE, "", Set.of(), vrf);
        byte[] encoded = AccountStateCborCodec.encodePoolRegistration(data);
        db.put(cfState, DefaultAccountStateStore.poolDepositKey(poolHash), encoded);
        db.put(cfState, DefaultAccountStateStore.poolParamsHistKey(poolHash, historyEpoch), encoded);
    }
}
