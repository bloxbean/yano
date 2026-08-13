package com.bloxbean.cardano.yano.api.archive;

import java.math.BigInteger;

/**
 * Optional durable capture boundary for facts that only exist while an epoch
 * transition is evaluated. Implementations must isolate failures from the
 * authoritative ledger transition; {@link #NOOP} allocates no per-row state.
 */
public interface EpochArchiveStagingSink {
    enum Dataset { EPOCH_STAKE, DREP_DISTRIBUTION, ADA_POT, GOVERNANCE_PROPOSAL_STATUS, REWARD }

    record Boundary(int previousEpoch, int newEpoch, long slot, long blockNumber) { }

    interface FactWriter<T> extends AutoCloseable {
        void append(T fact);
        void commit();
        default void abort() { }
        @Override default void close() { abort(); }
    }

    boolean enabled(Dataset dataset);

    default void beginBoundary(Boundary boundary) { }
    default void completeBoundary(Boundary boundary) { }
    default void abortBoundary(Boundary boundary) { }

    FactWriter<StakeFact> openStake(int epoch);
    FactWriter<DrepFact> openDrep(int epoch);
    FactWriter<AdaPotFact> openAdaPot(int epoch);
    FactWriter<GovernanceFact> openGovernance(int epoch, String part);
    FactWriter<RewardFact> openRewards(int archiveEpoch, String part);

    record StakeFact(int credentialType, String credentialHash, String poolHash, BigInteger amount) { }
    record DrepFact(int drepType, String credentialHash, BigInteger amount, Integer storedExpiry,
                    int dormantEpochs, Integer effectiveExpiry, boolean active) { }
    record AdaPotFact(BigInteger treasury, BigInteger reserves, BigInteger deposits, BigInteger fees,
                      BigInteger distributed, BigInteger undistributed, BigInteger rewardsPot,
                      BigInteger poolRewardsPot) { }
    record GovernanceFact(String txHash, int governanceActionIndex, String actionType,
                          String observationPhase, String statusCode, String decisionReason,
                          BigInteger deposit, String returnAddress, int submittedEpoch,
                          int expiresAfterEpoch) { }
    record RewardFact(int credentialType, String credentialHash, String poolHash, String rewardType,
                      int earnedEpoch, int spendableEpoch, BigInteger amount, String sourceId) { }

    EpochArchiveStagingSink NOOP = new EpochArchiveStagingSink() {
        private final FactWriter<Object> writer = new FactWriter<>() {
            public void append(Object ignored) { }
            public void commit() { }
        };
        public boolean enabled(Dataset ignored) { return false; }
        @SuppressWarnings("unchecked") private <T> FactWriter<T> writer() { return (FactWriter<T>) writer; }
        public FactWriter<StakeFact> openStake(int epoch) { return writer(); }
        public FactWriter<DrepFact> openDrep(int epoch) { return writer(); }
        public FactWriter<AdaPotFact> openAdaPot(int epoch) { return writer(); }
        public FactWriter<GovernanceFact> openGovernance(int epoch, String part) { return writer(); }
        public FactWriter<RewardFact> openRewards(int epoch, String part) { return writer(); }
    };
}
