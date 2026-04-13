package com.bloxbean.cardano.yano.ledgerstate;

import java.math.BigInteger;

/**
 * Test helper in the same package as AccountStateCborCodec to access package-private encoders.
 * Used by RocksDB-backed tests that need to populate data in the correct CBOR format.
 */
public class TestCborHelper {

    public static byte[] encodeDRepDelegation(int drepType, String drepHash, long slot, int txIdx, int certIdx) {
        return AccountStateCborCodec.encodeDRepDelegation(drepType, drepHash, slot, txIdx, certIdx);
    }

    public static byte[] encodeStakeAccount(BigInteger reward, BigInteger deposit) {
        return AccountStateCborCodec.encodeStakeAccount(reward, deposit);
    }

    public static byte[] encodePoolDelegation(String poolHash, long slot, int txIdx, int certIdx) {
        return AccountStateCborCodec.encodePoolDelegation(poolHash, slot, txIdx, certIdx);
    }

    public static byte[] encodeEpochDelegSnapshot(String poolHash, BigInteger amount) {
        return AccountStateCborCodec.encodeEpochDelegSnapshot(poolHash, amount);
    }

    public static byte[] encodeDRepRegistration(BigInteger deposit) {
        return AccountStateCborCodec.encodeDRepRegistration(deposit);
    }

    /** PREFIX_DREP_REG is package-private; expose for tests in other packages. */
    public static byte prefixDRepReg() {
        return DefaultAccountStateStore.PREFIX_DREP_REG;
    }

    public static AccountStateCborCodec.DRepDelegationRecord decodeDRepDelegation(byte[] val) {
        return AccountStateCborCodec.decodeDRepDelegation(val);
    }
}
