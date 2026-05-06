package com.bloxbean.cardano.yano.api.account;

import java.math.BigInteger;
import java.util.List;

/**
 * Read-only account history index for Blockfrost-style account history APIs.
 */
public interface AccountHistoryProvider {
    boolean isEnabled();

    boolean isHealthy();

    boolean isTxEventsEnabled();

    boolean isRewardsHistoryEnabled();

    List<WithdrawalRecord> getWithdrawals(int credType, String credHash, int page, int count);

    default List<WithdrawalRecord> getWithdrawals(int credType, String credHash, int page, int count, String order) {
        return getWithdrawals(credType, credHash, page, count);
    }

    List<DelegationRecord> getDelegations(int credType, String credHash, int page, int count);

    default List<DelegationRecord> getDelegations(int credType, String credHash, int page, int count, String order) {
        return getDelegations(credType, credHash, page, count);
    }

    List<RegistrationRecord> getRegistrations(int credType, String credHash, int page, int count);

    default List<RegistrationRecord> getRegistrations(int credType, String credHash, int page, int count, String order) {
        return getRegistrations(credType, credHash, page, count);
    }

    List<MirRecord> getMirs(int credType, String credHash, int page, int count);

    default List<MirRecord> getMirs(int credType, String credHash, int page, int count, String order) {
        return getMirs(credType, credHash, page, count);
    }

    record WithdrawalRecord(String txHash, BigInteger amount, long slot, long blockNo, int txIdx) {}

    record DelegationRecord(String txHash, String poolHash, long slot, long blockNo,
                            int txIdx, int certIdx, int activeEpoch) {}

    record RegistrationRecord(String txHash, String action, BigInteger deposit,
                              long slot, long blockNo, int txIdx, int certIdx) {}

    record MirRecord(String txHash, String pot, BigInteger amount, int earnedEpoch,
                     long slot, long blockNo, int txIdx, int certIdx) {}
}
