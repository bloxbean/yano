package com.bloxbean.cardano.yano.api.account;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only interface for querying ledger account state.
 * Used by transaction validation to build CertState for Scalus CardanoMutator.
 *
 * <p>credType: 0 = key hash, 1 = script hash (matches Cardano CBOR encoding).
 */
public interface LedgerStateProvider {

    // --- Delegation / Account State ---

    Optional<BigInteger> getRewardBalance(int credType, String credentialHash);

    Optional<BigInteger> getStakeDeposit(int credType, String credentialHash);

    Optional<String> getDelegatedPool(int credType, String credentialHash);

    default Optional<PoolDelegation> getPoolDelegation(int credType, String credentialHash) {
        return Optional.empty();
    }

    Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash);

    boolean isStakeCredentialRegistered(int credType, String credentialHash);

    default Optional<Long> getStakeRegistrationSlot(int credType, String credentialHash) {
        return Optional.empty();
    }

    BigInteger getTotalDeposited();

    // --- Pool State ---

    boolean isPoolRegistered(String poolHash);

    Optional<BigInteger> getPoolDeposit(String poolHash);

    Optional<Long> getPoolRetirementEpoch(String poolHash);

    /**
     * Get full pool registration parameters (latest/current).
     *
     * @param poolHash the pool operator hash (hex-encoded)
     * @return pool parameters, or empty if not registered
     */
    default Optional<PoolParams> getPoolParams(String poolHash) { return Optional.empty(); }

    /**
     * Get pool registration parameters that were active at a specific epoch.
     * Returns the latest registration that occurred at or before the given epoch.
     *
     * @param poolHash the pool operator hash (hex-encoded)
     * @param epoch    the epoch for which to retrieve params
     * @return pool parameters valid at that epoch, or empty if not registered
     */
    default Optional<PoolParams> getPoolParams(String poolHash, int epoch) {
        return getPoolParams(poolHash);
    }

    record PoolParams(BigInteger deposit, double margin, BigInteger cost, BigInteger pledge,
                      String rewardAccount, Set<String> owners) {}

    record PoolDelegation(String poolHash, long slot, int txIdx, int certIdx) {}

    // --- DRep State ---

    /**
     * Check if a DRep credential is registered.
     *
     * @param credType       0 = key hash, 1 = script hash
     * @param credentialHash the DRep credential hash (hex-encoded)
     * @return true if the DRep is registered
     */
    default boolean isDRepRegistered(int credType, String credentialHash) { return false; }

    /**
     * Get the deposit held for a registered DRep.
     */
    default Optional<BigInteger> getDRepDeposit(int credType, String credentialHash) { return Optional.empty(); }

    // --- Committee State ---

    /**
     * Check if a cold credential is an authorized committee member.
     */
    default boolean isCommitteeMember(int credType, String coldCredentialHash) { return false; }

    /**
     * Get the hot credential hash authorized for a committee member.
     */
    default Optional<String> getCommitteeHotCredential(int credType, String coldCredentialHash) { return Optional.empty(); }

    /**
     * Check if a committee member has resigned.
     */
    default boolean hasCommitteeMemberResigned(int credType, String coldCredentialHash) { return false; }

    // --- MIR (Move Instantaneous Rewards) State ---

    /**
     * Get the total instant reward amount for a credential from MIR certificates.
     * Returns the accumulated MIR reward for the credential (not yet claimed via withdrawal).
     *
     * <p>MIR certs exist in pre-Conway eras (Shelley through Babbage) and transfer ADA
     * from reserves or treasury to specific stake credentials. These are needed for
     * accurate reward calculation during mainnet replay.
     *
     * @param credType       0 = key hash, 1 = script hash
     * @param credentialHash the stake credential hash (hex-encoded)
     * @return the instant reward amount, or empty if no MIR rewards exist
     */
    default Optional<BigInteger> getInstantReward(int credType, String credentialHash) { return Optional.empty(); }

    /**
     * Get the total amount transferred between pots via MIR certs.
     *
     * @param toReserves true for treasury→reserves transfers, false for reserves→treasury
     * @return total pot transfer amount
     */
    default BigInteger getMirPotTransfer(boolean toReserves) { return BigInteger.ZERO; }

    // --- Epoch Delegation Snapshot Queries ---

    /**
     * Get the pool that a credential was delegated to at a specific epoch boundary.
     *
     * @param epoch          the epoch number
     * @param credType       0 = key hash, 1 = script hash
     * @param credentialHash the stake credential hash (hex-encoded)
     * @return the pool hash, or empty if no delegation existed at that epoch
     */
    default Optional<String> getEpochDelegation(int epoch, int credType, String credentialHash) {
        return Optional.empty();
    }

    /**
     * Get all credentials delegated to a specific pool at a given epoch.
     */
    record EpochDelegator(int credType, String credentialHash) {}
    default java.util.List<EpochDelegator> getPoolDelegatorsAtEpoch(int epoch, String poolHash) {
        return java.util.List.of();
    }

    /**
     * Get the latest epoch for which a delegation snapshot exists.
     * Returns -1 if no snapshots have been taken.
     */
    default int getLatestSnapshotEpoch() { return -1; }

    // --- Epoch Block Count and Fee Queries ---

    /**
     * Get the number of blocks produced by a pool in a given epoch.
     */
    default long getPoolBlockCount(int epoch, String poolHash) { return 0; }

    /**
     * Get all pool block counts for a given epoch.
     */
    default java.util.Map<String, Long> getPoolBlockCounts(int epoch) { return java.util.Map.of(); }

    /**
     * Get the total transaction fees collected in a given epoch.
     */
    default BigInteger getEpochFees(int epoch) { return BigInteger.ZERO; }

    // --- Retired Pool Queries ---

    /**
     * Get all pools retiring at a specific epoch boundary.
     *
     * @param retireEpoch the epoch at which pools retire
     * @return list of (poolHash, deposit) for pools whose retirement epoch matches
     */
    default java.util.List<RetiringPool> getPoolsRetiringAtEpoch(int retireEpoch) {
        return java.util.List.of();
    }

    record RetiringPool(String poolHash, BigInteger deposit, long retireEpoch) {}

    // --- Registered Credential Queries ---

    /**
     * Get all currently registered stake credential hashes.
     * Returns "credType:credHash" strings.
     */
    default java.util.Set<String> getAllRegisteredCredentials() {
        return java.util.Set.of();
    }

    /**
     * Get credentials whose last stake event in [startSlot, endSlot) is DEREGISTRATION.
     * Used for reward calculation to identify deregistered accounts within an epoch.
     *
     * @param startSlot inclusive start slot
     * @param endSlot   exclusive end slot
     * @return set of "credType:credHash" strings for deregistered credentials
     */
    default java.util.Set<String> getDeregisteredAccountsInSlotRange(long startSlot, long endSlot) {
        return java.util.Set.of();
    }

    /**
     * Get pool reward addresses that had a REGISTRATION event before cutoffSlot.
     * Used for reward calculation to identify registered pool reward addresses.
     *
     * @param cutoffSlot          exclusive cutoff slot
     * @param poolRewardAddresses set of "credType:credHash" pool reward addresses to filter
     * @return set of "credType:credHash" strings for registered pool reward addresses
     */
    default java.util.Set<String> getRegisteredPoolRewardAddressesBeforeSlot(long cutoffSlot, java.util.Set<String> poolRewardAddresses) {
        return java.util.Set.of();
    }

    // --- AdaPot Queries ---

    record AdaPotSnapshot(int epoch, BigInteger treasury, BigInteger reserves,
                          BigInteger deposits, BigInteger fees,
                          BigInteger distributedRewards, BigInteger undistributedRewards,
                          BigInteger rewardsPot, BigInteger poolRewardsPot) {}

    /**
     * Whether AdaPot tracking is enabled for this provider.
     */
    default boolean isAdaPotTrackingEnabled() { return false; }

    /**
     * Get the full AdaPot snapshot at a given epoch.
     */
    default Optional<AdaPotSnapshot> getAdaPot(int epoch) { return Optional.empty(); }

    /**
     * Get the latest stored AdaPot snapshot by scanning backwards from the given epoch.
     */
    default Optional<AdaPotSnapshot> getLatestAdaPot(int maxEpoch) {
        for (int epoch = maxEpoch; epoch >= 0; epoch--) {
            var adaPot = getAdaPot(epoch);
            if (adaPot.isPresent()) return adaPot;
        }
        return Optional.empty();
    }

    /**
     * Get the treasury balance at a given epoch.
     */
    default Optional<BigInteger> getTreasury(int epoch) { return Optional.empty(); }

    /**
     * Get the reserves balance at a given epoch.
     */
    default Optional<BigInteger> getReserves(int epoch) { return Optional.empty(); }

    // --- Protocol Parameters ---

    record ProtocolParamsSnapshot(
            int epoch,
            Integer minFeeA,
            Integer minFeeB,
            Integer maxBlockSize,
            Integer maxTxSize,
            Integer maxBlockHeaderSize,
            BigInteger keyDeposit,
            BigInteger poolDeposit,
            Integer eMax,
            Integer nOpt,
            BigDecimal a0,
            BigDecimal rho,
            BigDecimal tau,
            BigDecimal decentralisationParam,
            String extraEntropy,
            Integer protocolMajorVer,
            Integer protocolMinorVer,
            BigInteger minUtxo,
            BigInteger minPoolCost,
            String nonce,
            Map<String, Object> costModels,
            Map<String, Object> costModelsRaw,
            BigDecimal priceMem,
            BigDecimal priceStep,
            BigInteger maxTxExMem,
            BigInteger maxTxExSteps,
            BigInteger maxBlockExMem,
            BigInteger maxBlockExSteps,
            BigInteger maxValSize,
            Integer collateralPercent,
            Integer maxCollateralInputs,
            BigInteger coinsPerUtxoSize,
            BigInteger coinsPerUtxoWord,
            BigDecimal pvtMotionNoConfidence,
            BigDecimal pvtCommitteeNormal,
            BigDecimal pvtCommitteeNoConfidence,
            BigDecimal pvtHardForkInitiation,
            BigDecimal pvtPPSecurityGroup,
            BigDecimal dvtMotionNoConfidence,
            BigDecimal dvtCommitteeNormal,
            BigDecimal dvtCommitteeNoConfidence,
            BigDecimal dvtUpdateToConstitution,
            BigDecimal dvtHardForkInitiation,
            BigDecimal dvtPPNetworkGroup,
            BigDecimal dvtPPEconomicGroup,
            BigDecimal dvtPPTechnicalGroup,
            BigDecimal dvtPPGovGroup,
            BigDecimal dvtTreasuryWithdrawal,
            Integer committeeMinSize,
            Integer committeeMaxTermLength,
            Integer govActionLifetime,
            BigInteger govActionDeposit,
            BigInteger drepDeposit,
            Integer drepActivity,
            BigDecimal minFeeRefScriptCostPerByte) {}

    default Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
        return Optional.empty();
    }

    // --- Value types ---

    /**
     * DRep delegation target.
     *
     * @param drepType 0=ADDR_KEYHASH, 1=SCRIPTHASH, 2=ABSTAIN, 3=NO_CONFIDENCE
     * @param hash     credential hash (null for ABSTAIN/NO_CONFIDENCE)
     */
    record DRepDelegation(int drepType, String hash) {}
}
