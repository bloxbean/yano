package com.bloxbean.cardano.yano.api.account;

import java.math.BigInteger;
import java.util.List;

/**
 * Read-only account history index for Blockfrost-style account history APIs.
 */
public interface AccountHistoryProvider {
    /** Address-tx index scopes: hash key is 28-byte hex in every scope. */
    int ADDR_SCOPE_ADDRESS = 0;       // blake2b-224 of the full address bytes
    int ADDR_SCOPE_PAYMENT_CRED = 1;  // payment credential hash
    int ADDR_SCOPE_STAKE_CRED = 2;    // stake/delegation credential hash

    boolean isEnabled();

    boolean isHealthy();

    boolean isTxEventsEnabled();

    default boolean isAddressTxEnabled() {
        return false;
    }

    /**
     * Transactions that touched an address/credential (as input spender or
     * output receiver), block-ordered. Backing index is delta-tracked and
     * rollback-safe (ADR-033 M2).
     */
    default List<AddressTxRecord> getAddressTransactions(int scope, String hash28Hex,
                                                         int page, int count, String order) {
        return List.of();
    }

    /** True if the address/credential ever appeared in a transaction. */
    default boolean isAddressUsed(int scope, String hash28Hex) {
        return !getAddressTransactions(scope, hash28Hex, 1, 1, "asc").isEmpty();
    }

    /**
     * Convenience over {@link #getAddressTransactions(int, String, int, int, String)}
     * taking a bech32/hex address; the implementation derives the scope hash.
     */
    default List<AddressTxRecord> getAddressTransactionsForAddress(String address, boolean usePaymentCred,
                                                                   int page, int count, String order) {
        return List.of();
    }

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

    record AddressTxRecord(String txHash, long slot, long blockNo, int txIdx) {}

    /**
     * Per-epoch reward history rows (Blockfrost /accounts/{stake}/rewards);
     * populated at each epoch boundary when rewards history is enabled.
     */
    default List<RewardRecord> getRewards(int credType, String credHash, int page, int count, String order) {
        return List.of();
    }

    record RewardRecord(int earnedEpoch, BigInteger amount, String type, String poolHash, long slot) {}
}
