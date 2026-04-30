package com.bloxbean.cardano.yano.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import com.bloxbean.cardano.yano.ledgerstate.governance.epoch.DRepDistributionCalculator.DRepDistKey;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.RatificationResult;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.RatificationResult.Status;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.*;

/**
 * Core ratification logic — evaluates all active proposals against voting thresholds.
 * Port of yaci-store GovernanceEvaluationService + per-action evaluators.
 * <p>
 * Proposals are evaluated in priority order. Delaying actions (if ratified) prevent
 * subsequent lower-priority actions from being ratified in the same epoch.
 */
public class RatificationEngine {
    private static final Logger log = LoggerFactory.getLogger(RatificationEngine.class);

    private final GovernanceStateStore governanceStore;
    private final VoteTallyCalculator tallyCalculator;

    public RatificationEngine(GovernanceStateStore governanceStore,
                              VoteTallyCalculator tallyCalculator) {
        this.governanceStore = governanceStore;
        this.tallyCalculator = tallyCalculator;
    }

    // ===== Stateless Evaluation (testable, no store dependency) =====

    /**
     * Evaluate a single proposal using pre-computed tallies. Fully stateless — no DB access.
     * <p>
     * This is the core ratification logic matching the Cardano Conway spec (Amaru reference):
     * <ol>
     *   <li>Lifecycle check (expired / too fresh)</li>
     *   <li>Previous action chain validation</li>
     *   <li>Per-body threshold checks (committee → DRep → SPO) based on action type</li>
     * </ol>
     *
     * @param input             Pre-computed evaluation input (tallies + thresholds)
     * @param currentEpoch      Current epoch at boundary
     * @param isBootstrapPhase  Protocol v9 bootstrap (DRep thresholds = 0)
     * @param lastEnactedActions Last enacted action per type (for prev-action chain)
     * @param committeeState    "NORMAL" or "NO_CONFIDENCE"
     * @param committeeMinSize  Minimum active committee members required
     * @param committeeMaxTermLength Max committee member term
     * @param delayed           Whether a delaying action was already ratified this epoch
     * @return Ratification status
     */
    public static RatificationResult.Status evaluateStateless(
            ProposalEvaluationInput input,
            int currentEpoch,
            boolean isBootstrapPhase,
            Map<GovActionType, GovActionId> lastEnactedActions,
            String committeeState,
            int committeeMinSize,
            int committeeMaxTermLength,
            boolean delayed) {

        GovActionRecord proposal = input.proposal();
        GovActionType type = proposal.actionType();

        // 1. Lifecycle check
        boolean isExpired = (currentEpoch - proposal.expiresAfterEpoch()) > 1;
        boolean isLastChance = (currentEpoch - proposal.expiresAfterEpoch()) == 1;
        if (isExpired) return Status.EXPIRED;
        if (type == GovActionType.INFO_ACTION) return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        if (delayed) return isLastChance ? Status.EXPIRED : Status.ACTIVE;

        // 2. Previous action chain
        if (!prevActionValid(proposal, lastEnactedActions)) {
            return isLastChance ? Status.EXPIRED : Status.ACTIVE;
        }

        // 3. Per-body checks based on action type
        boolean accepted = switch (type) {
            case HARD_FORK_INITIATION_ACTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                if (!spoCheck(input)) yield false;
                yield true;
            }
            case PARAMETER_CHANGE_ACTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                // SPO only if threshold > 0 (security params)
                if (input.spoThreshold().compareTo(BigDecimal.ZERO) > 0 && !spoCheck(input)) yield false;
                yield true;
            }
            case TREASURY_WITHDRAWALS_ACTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                // Treasury balance check
                // TODO: sum all TW proposals' withdrawals vs treasury
                yield true;
            }
            case NO_CONFIDENCE -> {
                // Committee does NOT vote on its own dissolution
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                if (!spoCheck(input)) yield false;
                yield true;
            }
            case UPDATE_COMMITTEE -> {
                // Committee does NOT vote on its own update
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                if (!spoCheck(input)) yield false;
                yield true;
            }
            case NEW_CONSTITUTION -> {
                if (!committeeCheck(input, committeeState, isBootstrapPhase, committeeMinSize)) yield false;
                if (!isBootstrapPhase && !drepCheck(input)) yield false;
                yield true;
            }
            case INFO_ACTION -> false; // Already handled above
        };

        if (accepted) return Status.RATIFIED;
        return isLastChance ? Status.EXPIRED : Status.ACTIVE;
    }

    private static boolean committeeCheck(ProposalEvaluationInput input, String committeeState,
                                           boolean isBootstrapPhase, int committeeMinSize) {
        if ("NO_CONFIDENCE".equals(committeeState)) return false;
        var tally = input.committeeTally();
        if (!isBootstrapPhase) {
            int eligible = tally.yesCount() + tally.noCount() + tally.abstainCount();
            if (eligible < committeeMinSize) return false;
        }
        return VoteTallyCalculator.committeeThresholdMet(tally, input.committeeThreshold());
    }

    private static boolean drepCheck(ProposalEvaluationInput input) {
        return VoteTallyCalculator.drepThresholdMet(input.drepTally(), input.drepThreshold());
    }

    private static boolean spoCheck(ProposalEvaluationInput input) {
        return VoteTallyCalculator.spoThresholdMet(input.spoTally(), input.spoThreshold());
    }

    /**
     * Evaluate all active proposals for the epoch boundary.
     *
     * @param activeProposals    All currently active proposals
     * @param drepDist           DRep distribution (from DRepDistributionCalculator)
     * @param poolStakeDist      Pool → active stake
     * @param poolDRepDelegation Pool → DRep delegation type (for SPO default votes)
     * @param committeeMembers   Committee member states
     * @param committeeThreshold Committee quorum threshold
     * @param lastEnactedActions Last enacted action per type
     * @param currentEpoch       Current epoch (at boundary)
     * @param isBootstrapPhase   Whether in protocol v9 bootstrap
     * @param committeeMinSize   Min committee size (post-bootstrap check)
     * @param committeeState     "NORMAL" or "NO_CONFIDENCE"
     * @param treasury           Current treasury balance (for treasury withdrawal check)
     * @param drepThresholds     DRep voting thresholds per action type
     * @param spoThresholds      SPO voting thresholds per action type
     * @return List of ratification results per proposal
     */
    public List<RatificationResult> evaluateAll(
            Map<GovActionId, GovActionRecord> activeProposals,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold,
            Map<GovActionType, GovActionId> lastEnactedActions,
            int currentEpoch,
            boolean isBootstrapPhase,
            int committeeMinSize,
            int committeeMaxTermLength,
            String committeeState,
            BigInteger treasury,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds,
            DrepVoteThresholds effectiveDrepVotingThresholds)
            throws RocksDBException {

        // Sort proposals by priority (lower = higher priority) then by slot
        List<Map.Entry<GovActionId, GovActionRecord>> sorted = new ArrayList<>(activeProposals.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<GovActionId, GovActionRecord> e) ->
                        getActionPriority(e.getValue().actionType()))
                .thenComparingLong(e -> e.getValue().proposalSlot()));

        List<RatificationResult> results = new ArrayList<>();
        boolean delayed = false;

        if (log.isInfoEnabled()) {
            log.info("Ratification context: epoch={}, proposals={}, drepDist={}, activeDReps={}, pools={}, " +
                            "committeeMembers={}, activeCommittee={}, committeeMinSize={}, committeeMaxTermLength={}, " +
                            "committeeThreshold={}, committeeState={}, bootstrap={}, treasury={}, drepThresholds={}, spoThresholds={}",
                    currentEpoch, sorted.size(), drepDist.size(), activeDRepKeys != null ? activeDRepKeys.size() : -1,
                    poolStakeDist.size(), committeeMembers.size(), activeCommitteeCount(committeeMembers, currentEpoch),
                    committeeMinSize, committeeMaxTermLength, committeeThreshold, committeeState, isBootstrapPhase,
                    treasury, drepThresholds, spoThresholds);
        }

        for (var entry : sorted) {
            GovActionId id = entry.getKey();
            GovActionRecord proposal = entry.getValue();

            Status status = evaluateProposal(id, proposal, drepDist, activeDRepKeys,
                    poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, lastEnactedActions, currentEpoch,
                    isBootstrapPhase, committeeMinSize, committeeMaxTermLength,
                    committeeState, treasury, drepThresholds, spoThresholds,
                    effectiveDrepVotingThresholds, delayed);

            results.add(new RatificationResult(id, proposal, status));

            // Delaying actions prevent subsequent ratifications
            if (status == Status.RATIFIED && isDelayingAction(proposal.actionType())) {
                delayed = true;
            }
        }

        long ratified = results.stream().filter(RatificationResult::isRatified).count();
        long expired = results.stream().filter(RatificationResult::isExpired).count();
        log.info("Ratification results: {} ratified, {} expired, {} active (of {} total)",
                ratified, expired, results.size() - ratified - expired, results.size());
        for (var result : results) {
            log.info("  Proposal {}/{}: type={}, ratified={}, expired={}",
                    result.govActionId().getTransactionId().substring(0, 8),
                    result.govActionId().getGov_action_index(),
                    result.proposal() != null ? result.proposal().actionType() : "?",
                    result.isRatified(), result.isExpired());
        }

        return results;
    }

    /**
     * Evaluate a single proposal.
     */
    private Status evaluateProposal(
            GovActionId id, GovActionRecord proposal,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold,
            Map<GovActionType, GovActionId> lastEnactedActions,
            int currentEpoch, boolean isBootstrapPhase,
            int committeeMinSize, int committeeMaxTermLength,
            String committeeState,
            BigInteger treasury,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds,
            DrepVoteThresholds effectiveDrepVotingThresholds,
            boolean delayed) throws RocksDBException {

        GovActionType type = proposal.actionType();

        // Lifecycle check (per yaci-store RatificationContext):
        // expiresAfterEpoch = proposedInEpoch + govActionLifetime = maxAllowedVotingEpoch
        // isOutOfLifecycle: (currentEpoch - expiresAfterEpoch) > 1  → currentEpoch > expiresAfterEpoch + 1
        // isLastRatificationOpportunity: (currentEpoch - expiresAfterEpoch) == 1  → currentEpoch == expiresAfterEpoch + 1
        boolean isExpired = (currentEpoch - proposal.expiresAfterEpoch()) > 1;
        boolean isLastChance = (currentEpoch - proposal.expiresAfterEpoch()) == 1;

        Status earlyStatus = null;
        if (isExpired) {
            earlyStatus = Status.EXPIRED;
        } else if (type == GovActionType.INFO_ACTION) {
            // InfoAction can never be ratified
            earlyStatus = isLastChance ? Status.EXPIRED : Status.ACTIVE;
        } else if (delayed) {
            // If delayed by a prior delaying action this epoch
            earlyStatus = isLastChance ? Status.EXPIRED : Status.ACTIVE;
        } else if (!prevActionValid(proposal, lastEnactedActions)) {
            // Prev action check (for chained action types)
            earlyStatus = isLastChance ? Status.EXPIRED : Status.ACTIVE;
        }

        if (earlyStatus != null) {
            logEarlyRatificationDecision(id, proposal, earlyStatus, lastEnactedActions, currentEpoch, delayed);
            return earlyStatus;
        }

        // Get votes for this proposal
        Map<GovernanceStateStore.VoterKey, Integer> votes =
                governanceStore.getVotesForProposal(id.getTransactionId(), id.getGov_action_index());

        // Evaluate per action type
        boolean accepted = switch (type) {
            case HARD_FORK_INITIATION_ACTION -> evaluateHardFork(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, committeeState,
                    currentEpoch, isBootstrapPhase, committeeMinSize,
                    drepThresholds, spoThresholds);

            case PARAMETER_CHANGE_ACTION -> evaluateParameterChange(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    committeeMembers, committeeThreshold, committeeState,
                    currentEpoch, isBootstrapPhase, committeeMinSize,
                    drepThresholds, spoThresholds, proposal, effectiveDrepVotingThresholds);

            case TREASURY_WITHDRAWALS_ACTION -> evaluateTreasuryWithdrawal(
                    votes, drepDist, activeDRepKeys, committeeMembers, committeeThreshold,
                    committeeState, currentEpoch, isBootstrapPhase,
                    committeeMinSize, treasury, proposal, drepThresholds);

            case NO_CONFIDENCE -> evaluateNoConfidence(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    currentEpoch, isBootstrapPhase, drepThresholds, spoThresholds);

            case UPDATE_COMMITTEE -> evaluateUpdateCommittee(
                    votes, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                    currentEpoch, isBootstrapPhase, committeeState,
                    committeeMaxTermLength, proposal,
                    drepThresholds, spoThresholds);

            case NEW_CONSTITUTION -> evaluateNewConstitution(
                    votes, drepDist, activeDRepKeys, committeeMembers, committeeThreshold,
                    committeeState, currentEpoch, isBootstrapPhase,
                    committeeMinSize, drepThresholds);

            case INFO_ACTION -> false; // Already handled above
        };

        Status status = accepted ? Status.RATIFIED : (isLastChance ? Status.EXPIRED : Status.ACTIVE);
        logRatificationDecision(id, proposal, status, votes, drepDist, activeDRepKeys,
                poolStakeDist, poolDRepDelegation, committeeMembers, committeeThreshold,
                lastEnactedActions, currentEpoch, isBootstrapPhase, committeeMinSize,
                committeeMaxTermLength, committeeState, treasury, drepThresholds,
                spoThresholds, effectiveDrepVotingThresholds, delayed);
        return status;
    }

    // ===== Per-Action-Type Evaluators =====

    private boolean evaluateHardFork(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        boolean committeePassed = checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize);
        log.debug("HardFork eval: committee={}, threshold={}, bootstrap={}", committeePassed, committeeThreshold, isBootstrapPhase);
        if (!committeePassed) return false;

        boolean spoPassed = checkSPO(votes, poolStakeDist, poolDRepDelegation,
                GovActionType.HARD_FORK_INITIATION_ACTION, isBootstrapPhase, spoThresholds);
        log.debug("HardFork eval: spo={}, spoThreshold={}", spoPassed, spoThresholds.get(GovActionType.HARD_FORK_INITIATION_ACTION));
        if (!spoPassed) return false;

        if (!isBootstrapPhase) {
            boolean drepPassed = checkDRep(votes, drepDist, activeDRepKeys,
                    GovActionType.HARD_FORK_INITIATION_ACTION, drepThresholds);
            log.debug("HardFork eval: drep={}", drepPassed);
            if (!drepPassed) return false;
        }
        return true;
    }

    private boolean evaluateParameterChange(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds,
            GovActionRecord proposal,
            DrepVoteThresholds effectiveDrepVotingThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (isBootstrapPhase) return true;

        // DRep threshold for a ParameterChange is the MAX threshold across affected groups
        // (network / economic / technical / governance). Security group is handled by SPO.
        // Per Cardano Conway spec; see Haskell `votingDRepThreshold` and Amaru
        // `governance/ratification/dreps.rs::voting_threshold`.
        List<ProtocolParamGroupClassifier.ParamGroup> affectedGroups = List.of();
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.ParameterChangeAction pca
                && pca.getProtocolParamUpdate() != null) {
            affectedGroups = ProtocolParamGroupClassifier.getAffectedGroups(pca.getProtocolParamUpdate());
        }
        BigDecimal perProposalDrepThreshold =
                ProtocolParamGroupClassifier.computeDRepThreshold(affectedGroups, effectiveDrepVotingThresholds);
        var tally = tallyCalculator.computeDRepTally(votes, drepDist, GovActionType.PARAMETER_CHANGE_ACTION, activeDRepKeys);
        if (!VoteTallyCalculator.drepThresholdMet(tally, perProposalDrepThreshold)) return false;

        // SPO vote required only for security-group parameter changes.
        if (ProtocolParamGroupClassifier.isSpoVotingRequired(affectedGroups)) {
            if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                    GovActionType.PARAMETER_CHANGE_ACTION, isBootstrapPhase, spoThresholds)) return false;
        }
        return true;
    }

    private boolean evaluateTreasuryWithdrawal(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            BigInteger treasury, GovActionRecord proposal,
            Map<GovActionType, BigDecimal> drepThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
                GovActionType.TREASURY_WITHDRAWALS_ACTION, drepThresholds)) return false;

        // Treasury balance check: totalWithdrawal <= treasury
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction twa) {
            BigInteger totalWithdrawal = BigInteger.ZERO;
            if (twa.getWithdrawals() != null) {
                for (BigInteger amount : twa.getWithdrawals().values()) {
                    totalWithdrawal = totalWithdrawal.add(amount);
                }
            }
            if (totalWithdrawal.compareTo(treasury) > 0) {
                return false; // Cannot withdraw more than treasury balance
            }
        }
        return true;
    }

    private boolean evaluateNoConfidence(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            int currentEpoch, boolean isBootstrapPhase,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        // No committee vote for NoConfidence
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
                GovActionType.NO_CONFIDENCE, drepThresholds)) return false;
        if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                GovActionType.NO_CONFIDENCE, isBootstrapPhase, spoThresholds)) return false;
        return true;
    }

    private boolean evaluateUpdateCommittee(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            int currentEpoch, boolean isBootstrapPhase, String committeeState,
            int committeeMaxTermLength, GovActionRecord proposal,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds) {

        // No committee vote for UpdateCommittee
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
                GovActionType.UPDATE_COMMITTEE, drepThresholds)) return false;
        if (!checkSPO(votes, poolStakeDist, poolDRepDelegation,
                GovActionType.UPDATE_COMMITTEE, isBootstrapPhase, spoThresholds)) return false;

        // Term validation: all new members' expiration must be <= currentEpoch + maxTermLength
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee uc) {
            if (uc.getNewMembersAndTerms() != null) {
                int maxExpiry = currentEpoch + committeeMaxTermLength;
                for (Integer expiryEpoch : uc.getNewMembersAndTerms().values()) {
                    if (expiryEpoch != null && expiryEpoch > maxExpiry) {
                        return false; // Member term exceeds maximum allowed
                    }
                }
            }
        }
        return true;
    }

    private boolean evaluateNewConstitution(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize,
            Map<GovActionType, BigDecimal> drepThresholds) {

        if (!checkCommittee(votes, committeeMembers, committeeThreshold, committeeState,
                currentEpoch, isBootstrapPhase, committeeMinSize)) return false;
        if (!isBootstrapPhase && !checkDRep(votes, drepDist, activeDRepKeys,
                GovActionType.NEW_CONSTITUTION, drepThresholds)) return false;
        return true;
    }

    // ===== Threshold Check Helpers =====

    private boolean checkCommittee(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<CredentialKey, CommitteeMemberRecord> members,
            BigDecimal threshold, String committeeState,
            int currentEpoch, boolean isBootstrapPhase, int committeeMinSize) {

        if ("NO_CONFIDENCE".equals(committeeState)) return false;

        // Post-bootstrap: check min committee size
        if (!isBootstrapPhase) {
            long activeCount = members.values().stream()
                    .filter(m -> !m.resigned() && m.expiryEpoch() >= currentEpoch)
                    .count();
            if (activeCount < committeeMinSize) return false;
        }

        var tally = tallyCalculator.computeCommitteeTally(votes, members, currentEpoch);
        return VoteTallyCalculator.committeeThresholdMet(tally, threshold);
    }

    private boolean checkDRep(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            GovActionType actionType,
            Map<GovActionType, BigDecimal> thresholds) {

        BigDecimal threshold = thresholds.getOrDefault(actionType, BigDecimal.ONE);
        var tally = tallyCalculator.computeDRepTally(votes, drepDist, actionType, activeDRepKeys);
        boolean result = VoteTallyCalculator.drepThresholdMet(tally, threshold);

        if (log.isDebugEnabled()) {
            BigInteger denom = tally.yesStake().add(tally.noStake());
            String ratio = denom.signum() == 0 ? "N/A" :
                    new BigDecimal(tally.yesStake()).divide(new BigDecimal(denom),
                            java.math.MathContext.DECIMAL128).toPlainString();
            log.debug("  DRep tally [{}]: yes={}, no={}, abstain={}, ratio={}, threshold={}, passed={}",
                    actionType, tally.yesStake(), tally.noStake(), tally.abstainStake(),
                    ratio, threshold, result);
        }
        return result;
    }

    private boolean checkSPO(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            GovActionType actionType, boolean isBootstrapPhase,
            Map<GovActionType, BigDecimal> thresholds) {

        BigDecimal threshold = thresholds.getOrDefault(actionType, BigDecimal.ONE);
        var tally = tallyCalculator.computeSPOTally(votes, poolStakeDist, poolDRepDelegation,
                actionType, isBootstrapPhase);
        return VoteTallyCalculator.spoThresholdMet(tally, threshold);
    }

    private void logEarlyRatificationDecision(GovActionId id, GovActionRecord proposal, Status status,
                                              Map<GovActionType, GovActionId> lastEnactedActions,
                                              int currentEpoch, boolean delayed) {
        if (!log.isInfoEnabled()) return;

        GovActionType type = proposal.actionType();
        boolean isExpired = (currentEpoch - proposal.expiresAfterEpoch()) > 1;
        String reason;
        if (isExpired) {
            reason = "expired";
        } else if (type == GovActionType.INFO_ACTION) {
            reason = "info_action_not_ratifiable";
        } else if (delayed) {
            reason = "delayed_by_prior_action";
        } else if (!prevActionValid(proposal, lastEnactedActions)) {
            reason = "prev_action_invalid";
        } else {
            reason = "early";
        }

        log.info("Ratification decision: {}/{} type={} status={} reason={} proposed={} expires={} delayed={}",
                shortTx(id), id.getGov_action_index(), type, status, reason,
                proposal.proposedInEpoch(), proposal.expiresAfterEpoch(), delayed);
    }

    private void logRatificationDecision(
            GovActionId id, GovActionRecord proposal, Status status,
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold,
            Map<GovActionType, GovActionId> lastEnactedActions,
            int currentEpoch, boolean isBootstrapPhase,
            int committeeMinSize, int committeeMaxTermLength,
            String committeeState,
            BigInteger treasury,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds,
            DrepVoteThresholds effectiveDrepVotingThresholds,
            boolean delayed) {

        if (!log.isInfoEnabled()) return;

        RatificationDiagnostics diagnostics = buildDiagnostics(proposal, votes, drepDist, activeDRepKeys,
                poolStakeDist, poolDRepDelegation, committeeMembers, committeeThreshold,
                lastEnactedActions, currentEpoch, isBootstrapPhase, committeeMinSize,
                committeeMaxTermLength, committeeState, treasury, drepThresholds,
                spoThresholds, effectiveDrepVotingThresholds, delayed);

        var committee = diagnostics.committeeTally();
        var drep = diagnostics.drepTally();
        var spo = diagnostics.spoTally();

        log.info("Ratification decision: {}/{} type={} status={} reason={} proposed={} expires={} votes={} " +
                        "committee=yes:{},no:{},abstain:{},ratio:{},threshold:{},active:{},min:{} " +
                        "drep=yes:{},no:{},abstain:{},ratio:{},threshold:{} " +
                        "spo=yes:{},no:{},abstain:{},total:{},ratio:{},threshold:{} " +
                        "spoRequired={} groups={} delayed={}",
                shortTx(id), id.getGov_action_index(), proposal.actionType(), status, diagnostics.reason(),
                proposal.proposedInEpoch(), proposal.expiresAfterEpoch(), votes.size(),
                committee != null ? committee.yesCount() : null,
                committee != null ? committee.noCount() : null,
                committee != null ? committee.abstainCount() : null,
                ratioString(diagnostics.committeeRatio()), committeeThreshold,
                diagnostics.activeCommitteeCount(), committeeMinSize,
                drep != null ? drep.yesStake() : null,
                drep != null ? drep.noStake() : null,
                drep != null ? drep.abstainStake() : null,
                ratioString(diagnostics.drepRatio()), diagnostics.drepThreshold(),
                spo != null ? spo.yesStake() : null,
                spo != null ? spo.noStake() : null,
                spo != null ? spo.abstainStake() : null,
                spo != null ? spo.totalStake() : null,
                ratioString(diagnostics.spoRatio()), diagnostics.spoThreshold(),
                diagnostics.spoRequired(), diagnostics.affectedGroups(), delayed);
    }

    private RatificationDiagnostics buildDiagnostics(
            GovActionRecord proposal,
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<DRepDistKey, BigInteger> drepDist,
            Set<DRepDistKey> activeDRepKeys,
            Map<String, BigInteger> poolStakeDist,
            Map<String, Integer> poolDRepDelegation,
            Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
            BigDecimal committeeThreshold,
            Map<GovActionType, GovActionId> lastEnactedActions,
            int currentEpoch, boolean isBootstrapPhase,
            int committeeMinSize, int committeeMaxTermLength,
            String committeeState,
            BigInteger treasury,
            Map<GovActionType, BigDecimal> drepThresholds,
            Map<GovActionType, BigDecimal> spoThresholds,
            DrepVoteThresholds effectiveDrepVotingThresholds,
            boolean delayed) {

        GovActionType type = proposal.actionType();
        var committeeTally = tallyCalculator.computeCommitteeTally(votes, committeeMembers, currentEpoch);
        long activeCommitteeCount = activeCommitteeCount(committeeMembers, currentEpoch);
        BigDecimal committeeRatio = committeeRatio(committeeTally);
        VoteTallyCalculator.DRepTally drepTally = null;
        VoteTallyCalculator.SPOTally spoTally = null;
        BigDecimal drepThreshold = null;
        BigDecimal spoThreshold = null;
        BigDecimal drepRatio = null;
        BigDecimal spoRatio = null;
        List<ProtocolParamGroupClassifier.ParamGroup> affectedGroups = List.of();
        boolean spoRequired = false;

        boolean isExpired = (currentEpoch - proposal.expiresAfterEpoch()) > 1;
        String reason;
        if (isExpired) {
            reason = "expired";
        } else if (type == GovActionType.INFO_ACTION) {
            reason = "info_action_not_ratifiable";
        } else if (delayed) {
            reason = "delayed_by_prior_action";
        } else if (!prevActionValid(proposal, lastEnactedActions)) {
            reason = "prev_action_invalid";
        } else {
            reason = "accepted";
            if (requiresCommitteeVote(type)) {
                reason = committeeFailureReason(committeeState, isBootstrapPhase, activeCommitteeCount,
                        committeeMinSize, committeeTally, committeeThreshold);
                if (reason == null) reason = "accepted";
            }

            if ("accepted".equals(reason) && requiresDRepVote(type, isBootstrapPhase)) {
                drepThreshold = drepThreshold(type, proposal, drepThresholds, effectiveDrepVotingThresholds);
                if (type == GovActionType.PARAMETER_CHANGE_ACTION) {
                    affectedGroups = affectedParamGroups(proposal);
                }
                drepTally = tallyCalculator.computeDRepTally(votes, drepDist, type, activeDRepKeys);
                drepRatio = drepRatio(drepTally);
                if (!VoteTallyCalculator.drepThresholdMet(drepTally, drepThreshold)) {
                    reason = "drep_threshold";
                }
            }

            if ("accepted".equals(reason)) {
                spoRequired = requiresSpoVote(type, proposal, affectedGroups);
                if (spoRequired) {
                    spoThreshold = spoThresholds.getOrDefault(type, BigDecimal.ONE);
                    spoTally = tallyCalculator.computeSPOTally(votes, poolStakeDist, poolDRepDelegation,
                            type, isBootstrapPhase);
                    spoRatio = spoRatio(spoTally);
                    if (!VoteTallyCalculator.spoThresholdMet(spoTally, spoThreshold)) {
                        reason = "spo_threshold";
                    }
                }
            }

            if ("accepted".equals(reason) && type == GovActionType.TREASURY_WITHDRAWALS_ACTION
                    && treasuryWithdrawalTotal(proposal).compareTo(treasury) > 0) {
                reason = "treasury_balance";
            }

            if ("accepted".equals(reason) && type == GovActionType.UPDATE_COMMITTEE
                    && updateCommitteeTermExceedsMax(proposal, currentEpoch, committeeMaxTermLength)) {
                reason = "committee_term";
            }
        }

        if (drepTally == null && requiresDRepVote(type, isBootstrapPhase)) {
            drepThreshold = drepThreshold(type, proposal, drepThresholds, effectiveDrepVotingThresholds);
            if (type == GovActionType.PARAMETER_CHANGE_ACTION) {
                affectedGroups = affectedParamGroups(proposal);
            }
            drepTally = tallyCalculator.computeDRepTally(votes, drepDist, type, activeDRepKeys);
            drepRatio = drepRatio(drepTally);
        }

        if (spoTally == null) {
            spoRequired = requiresSpoVote(type, proposal, affectedGroups);
            if (spoRequired) {
                spoThreshold = spoThresholds.getOrDefault(type, BigDecimal.ONE);
                spoTally = tallyCalculator.computeSPOTally(votes, poolStakeDist, poolDRepDelegation,
                        type, isBootstrapPhase);
                spoRatio = spoRatio(spoTally);
            }
        }

        return new RatificationDiagnostics(reason, committeeTally, activeCommitteeCount, committeeRatio,
                drepThreshold, drepTally, drepRatio, spoThreshold, spoTally, spoRatio,
                affectedGroups, spoRequired);
    }

    private static boolean requiresCommitteeVote(GovActionType type) {
        return switch (type) {
            case HARD_FORK_INITIATION_ACTION, PARAMETER_CHANGE_ACTION, TREASURY_WITHDRAWALS_ACTION,
                 NEW_CONSTITUTION -> true;
            case NO_CONFIDENCE, UPDATE_COMMITTEE, INFO_ACTION -> false;
        };
    }

    private static boolean requiresDRepVote(GovActionType type, boolean isBootstrapPhase) {
        return !isBootstrapPhase && switch (type) {
            case HARD_FORK_INITIATION_ACTION, PARAMETER_CHANGE_ACTION, TREASURY_WITHDRAWALS_ACTION,
                 NO_CONFIDENCE, UPDATE_COMMITTEE, NEW_CONSTITUTION -> true;
            case INFO_ACTION -> false;
        };
    }

    private static boolean requiresSpoVote(GovActionType type, GovActionRecord proposal,
                                           List<ProtocolParamGroupClassifier.ParamGroup> affectedGroups) {
        return switch (type) {
            case HARD_FORK_INITIATION_ACTION, NO_CONFIDENCE, UPDATE_COMMITTEE -> true;
            case PARAMETER_CHANGE_ACTION -> ProtocolParamGroupClassifier.isSpoVotingRequired(
                    affectedGroups != null && !affectedGroups.isEmpty() ? affectedGroups : affectedParamGroups(proposal));
            case TREASURY_WITHDRAWALS_ACTION, NEW_CONSTITUTION, INFO_ACTION -> false;
        };
    }

    private static BigDecimal drepThreshold(GovActionType type, GovActionRecord proposal,
                                            Map<GovActionType, BigDecimal> drepThresholds,
                                            DrepVoteThresholds effectiveDrepVotingThresholds) {
        if (type == GovActionType.PARAMETER_CHANGE_ACTION) {
            return ProtocolParamGroupClassifier.computeDRepThreshold(
                    affectedParamGroups(proposal), effectiveDrepVotingThresholds);
        }
        return drepThresholds.getOrDefault(type, BigDecimal.ONE);
    }

    private static List<ProtocolParamGroupClassifier.ParamGroup> affectedParamGroups(GovActionRecord proposal) {
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.ParameterChangeAction pca
                && pca.getProtocolParamUpdate() != null) {
            return ProtocolParamGroupClassifier.getAffectedGroups(pca.getProtocolParamUpdate());
        }
        return List.of();
    }

    private static String committeeFailureReason(String committeeState, boolean isBootstrapPhase,
                                                 long activeCommitteeCount, int committeeMinSize,
                                                 VoteTallyCalculator.CommitteeTally tally,
                                                 BigDecimal threshold) {
        if ("NO_CONFIDENCE".equals(committeeState)) return "committee_no_confidence";
        if (!isBootstrapPhase && activeCommitteeCount < committeeMinSize) return "committee_min_size";
        if (!VoteTallyCalculator.committeeThresholdMet(tally, threshold)) return "committee_threshold";
        return null;
    }

    private static BigInteger treasuryWithdrawalTotal(GovActionRecord proposal) {
        BigInteger totalWithdrawal = BigInteger.ZERO;
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction twa
                && twa.getWithdrawals() != null) {
            for (BigInteger amount : twa.getWithdrawals().values()) {
                totalWithdrawal = totalWithdrawal.add(amount);
            }
        }
        return totalWithdrawal;
    }

    private static boolean updateCommitteeTermExceedsMax(GovActionRecord proposal, int currentEpoch,
                                                         int committeeMaxTermLength) {
        if (proposal.govAction() instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee uc
                && uc.getNewMembersAndTerms() != null) {
            int maxExpiry = currentEpoch + committeeMaxTermLength;
            for (Integer expiryEpoch : uc.getNewMembersAndTerms().values()) {
                if (expiryEpoch != null && expiryEpoch > maxExpiry) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long activeCommitteeCount(Map<CredentialKey, CommitteeMemberRecord> members, int currentEpoch) {
        return members.values().stream()
                .filter(m -> !m.resigned() && m.expiryEpoch() >= currentEpoch)
                .count();
    }

    private static BigDecimal committeeRatio(VoteTallyCalculator.CommitteeTally tally) {
        int denominator = tally.yesCount() + tally.noCount();
        if (denominator == 0) return null;
        return BigDecimal.valueOf(tally.yesCount()).divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL128);
    }

    private static BigDecimal drepRatio(VoteTallyCalculator.DRepTally tally) {
        BigInteger denominator = tally.yesStake().add(tally.noStake());
        if (denominator.signum() == 0) return null;
        return new BigDecimal(tally.yesStake()).divide(new BigDecimal(denominator), MathContext.DECIMAL128);
    }

    private static BigDecimal spoRatio(VoteTallyCalculator.SPOTally tally) {
        BigInteger denominator = tally.totalStake().subtract(tally.abstainStake());
        if (denominator.signum() <= 0) return null;
        return new BigDecimal(tally.yesStake()).divide(new BigDecimal(denominator), MathContext.DECIMAL128);
    }

    private static String ratioString(BigDecimal ratio) {
        return ratio == null ? "n/a" : ratio.stripTrailingZeros().toPlainString();
    }

    private static String shortTx(GovActionId id) {
        String tx = id.getTransactionId();
        return tx != null && tx.length() > 8 ? tx.substring(0, 8) : String.valueOf(tx);
    }

    private record RatificationDiagnostics(
            String reason,
            VoteTallyCalculator.CommitteeTally committeeTally,
            long activeCommitteeCount,
            BigDecimal committeeRatio,
            BigDecimal drepThreshold,
            VoteTallyCalculator.DRepTally drepTally,
            BigDecimal drepRatio,
            BigDecimal spoThreshold,
            VoteTallyCalculator.SPOTally spoTally,
            BigDecimal spoRatio,
            List<ProtocolParamGroupClassifier.ParamGroup> affectedGroups,
            boolean spoRequired) {
    }

    // ===== Prev Action Validation =====

    static boolean prevActionValid(GovActionRecord proposal,
                                    Map<GovActionType, GovActionId> lastEnactedActions) {
        GovActionType type = proposal.actionType();

        // Action types without prev action chains
        if (type == GovActionType.TREASURY_WITHDRAWALS_ACTION || type == GovActionType.INFO_ACTION) {
            return true;
        }

        // Map action type to the purpose type for last-enacted lookup
        GovActionType purposeType = switch (type) {
            case NO_CONFIDENCE, UPDATE_COMMITTEE -> GovActionType.UPDATE_COMMITTEE; // shared purpose
            default -> type;
        };

        GovActionId lastEnacted = lastEnactedActions.get(purposeType);
        String prevTxHash = proposal.prevActionTxHash();
        Integer prevIdx = proposal.prevActionIndex();

        if (prevTxHash == null && lastEnacted == null) return true; // Both null = genesis
        if (prevTxHash == null || lastEnacted == null) return false; // One null, other not
        return prevTxHash.equals(lastEnacted.getTransactionId())
                && prevIdx != null && prevIdx.equals(lastEnacted.getGov_action_index());
    }

    // ===== Priority & Delaying =====

    static int getActionPriority(GovActionType type) {
        return switch (type) {
            case NO_CONFIDENCE -> 0;
            case UPDATE_COMMITTEE -> 1;
            case NEW_CONSTITUTION -> 2;
            case HARD_FORK_INITIATION_ACTION -> 3;
            case PARAMETER_CHANGE_ACTION -> 4;
            case TREASURY_WITHDRAWALS_ACTION -> 5;
            case INFO_ACTION -> 6;
        };
    }

    static boolean isDelayingAction(GovActionType type) {
        return switch (type) {
            case NO_CONFIDENCE, UPDATE_COMMITTEE, NEW_CONSTITUTION,
                 HARD_FORK_INITIATION_ACTION, PARAMETER_CHANGE_ACTION -> true;
            default -> false;
        };
    }
}
