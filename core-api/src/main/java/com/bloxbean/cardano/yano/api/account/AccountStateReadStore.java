package com.bloxbean.cardano.yano.api.account;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * API-oriented read contract for account, stake snapshot, and governance state.
 *
 * <p>This keeps REST/query projections separate from the ledger-state methods
 * used by block processing and transaction validation.
 */
public interface AccountStateReadStore {

    record EpochStake(int epoch, int credType, String credentialHash, String poolHash, BigInteger amount) {}

    record PoolStake(int epoch, String poolHash, BigInteger amount) {}

    record PoolStakeDelegator(int epoch, int credType, String credentialHash, String poolHash, BigInteger amount) {}

    record GovernanceProposal(
            String txHash,
            int certIndex,
            String govActionId,
            String status,
            String actionType,
            BigInteger deposit,
            String returnAddress,
            int proposedInEpoch,
            int expiresAfterEpoch,
            String prevActionTxHash,
            Integer prevActionIndex,
            long proposalSlot
    ) {}

    record GovernanceVote(int voterType, String voterHash, int vote) {}

    record DRepInfo(
            int drepType,
            String drepHash,
            BigInteger deposit,
            String anchorUrl,
            String anchorHash,
            int registeredAtEpoch,
            Integer lastInteractionEpoch,
            int expiryEpoch,
            boolean active,
            long registeredAtSlot,
            int protocolVersionAtRegistration,
            Long previousDeregistrationSlot
    ) {}

    default Optional<EpochStake> getEpochStake(int epoch, int credType, String credentialHash) {
        return Optional.empty();
    }

    default Optional<BigInteger> getTotalActiveStake(int epoch) {
        return Optional.empty();
    }

    default Optional<PoolStake> getPoolActiveStake(int epoch, String poolHash) {
        return Optional.empty();
    }

    default List<PoolStakeDelegator> listPoolStakeDelegators(int epoch, String poolHash, int page, int count,
                                                            String order) {
        return List.of();
    }

    default List<GovernanceProposal> listGovernanceProposals() {
        return List.of();
    }

    default Optional<GovernanceProposal> getGovernanceProposal(String txHash, int certIndex) {
        return Optional.empty();
    }

    default List<GovernanceVote> getGovernanceProposalVotes(String txHash, int certIndex) {
        return List.of();
    }

    default List<DRepInfo> listDReps() {
        return List.of();
    }

    default Optional<DRepInfo> getDRep(int drepType, String drepHash) {
        return Optional.empty();
    }

    default Optional<BigInteger> getDRepDistribution(int epoch, int drepType, String drepHash) {
        return Optional.empty();
    }

    default Optional<Integer> getLatestDRepDistributionEpoch(int maxEpoch) {
        return Optional.empty();
    }
}
