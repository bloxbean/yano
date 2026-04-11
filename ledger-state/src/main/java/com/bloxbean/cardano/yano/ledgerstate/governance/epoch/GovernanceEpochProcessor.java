package com.bloxbean.cardano.yano.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.ledgerstate.AccountStateCborCodec;
import com.bloxbean.cardano.yano.ledgerstate.AdaPotTracker;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yano.ledgerstate.governance.ratification.ProtocolParamGroupClassifier;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import com.bloxbean.cardano.yano.ledgerstate.governance.epoch.DRepDistributionCalculator.DRepDistKey;
import java.util.Set;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.RatificationResult;
import com.bloxbean.cardano.yano.ledgerstate.governance.ratification.EnactmentProcessor;
import com.bloxbean.cardano.yano.ledgerstate.governance.ratification.ProposalDropService;
import com.bloxbean.cardano.yano.ledgerstate.governance.ratification.RatificationEngine;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates all governance processing at epoch boundaries.
 * Called from EpochBoundaryProcessor.processEpochBoundary() BEFORE reward calculation.
 * <p>
 * Processing order (must match Haskell ledger):
 * <ol>
 *   <li>Calculate DRep distribution</li>
 *   <li>RATIFY: Evaluate all active proposals against vote thresholds</li>
 *   <li>ENACT: Apply ratified actions</li>
 *   <li>Remove expired + conflicting proposals, refund deposits</li>
 *   <li>Update DRep expiry</li>
 *   <li>Update dormant epoch tracking</li>
 *   <li>Process donations</li>
 * </ol>
 */
public class GovernanceEpochProcessor {
    private static final Logger log = LoggerFactory.getLogger(GovernanceEpochProcessor.class);

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private final ColumnFamilyHandle cfDelta;
    private final GovernanceStateStore governanceStore;
    private final DRepDistributionCalculator drepDistCalculator;
    private final DRepExpiryCalculator drepExpiryCalculator;
    private final RatificationEngine ratificationEngine;
    private final EnactmentProcessor enactmentProcessor;
    private final ProposalDropService proposalDropService;
    private final EpochParamProvider paramProvider;
    private final EpochParamTracker paramTracker;
    private final AdaPotTracker adaPotTracker;
    private final PoolStakeResolver poolStakeResolver;
    private final RewardRestStore rewardRestStore;

    // Optional epoch snapshot exporter for debugging (NOOP when disabled)
    private com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter snapshotExporter =
            com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.NOOP;

    // Conway era first epoch (network-specific, set during bootstrap)
    private int conwayFirstEpoch = -1;
    private volatile boolean genesisBootstrapped = false;
    private final com.bloxbean.cardano.yano.ledgerstate.governance.ConwayGenesisBootstrap genesisBootstrap;

    /**
     * Resolves pool stake distribution and pool-to-DRep delegation mapping for SPO voting.
     * Implemented by the caller (DefaultAccountStateStore) which has access to epoch snapshots.
     */
    public interface PoolStakeResolver {
        PoolStakeData resolvePoolStake(int epoch) throws RocksDBException;
    }

    /** Pool stake distribution and DRep delegation data for SPO voting. */
    public record PoolStakeData(
            Map<String, BigInteger> poolStakes,
            Map<String, Integer> poolDRepDelegations
    ) {
        public static final PoolStakeData EMPTY = new PoolStakeData(Map.of(), Map.of());
    }

    /**
     * Stores a deferred reward (reward_rest) that becomes spendable and counts toward
     * stake at a future epoch. Used for proposal deposit refunds and treasury withdrawals.
     */
    public interface RewardRestStore {
        /**
         * Store a reward_rest entry.
         *
         * @param spendableEpoch   Epoch when this reward becomes part of the stake snapshot
         * @param type             Reward type byte (REWARD_REST_PROPOSAL_REFUND, etc.)
         * @param rewardAccountHex Reward account in hex (header + credential hash)
         * @param amount           Amount in lovelace
         * @param earnedEpoch      Epoch when the reward was earned
         * @param slot             Slot of the triggering event
         * @param batch            WriteBatch for atomic writes
         * @param deltaOps         Delta ops for rollback
         * @return true if stored (valid address), false if invalid
         */
        boolean storeRewardRest(int spendableEpoch, byte type, String rewardAccountHex,
                                BigInteger amount, int earnedEpoch, long slot,
                                WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException;

        /** Get all spendable reward_rest entries with spendableEpoch <= epoch. */
        java.util.Map<String, BigInteger> getSpendableRewardRest(int epoch);
    }

    /** Result of governance Phase 1 (enactment + treasury withdrawals + deposit refunds). */
    record EnactmentResult(BigInteger treasuryDelta, BigInteger depositRefunds) {}

    public GovernanceEpochProcessor(RocksDB db, ColumnFamilyHandle cfState, ColumnFamilyHandle cfDelta,
                                    GovernanceStateStore governanceStore,
                                    DRepDistributionCalculator drepDistCalculator,
                                    DRepExpiryCalculator drepExpiryCalculator,
                                    RatificationEngine ratificationEngine,
                                    EnactmentProcessor enactmentProcessor,
                                    ProposalDropService proposalDropService,
                                    EpochParamProvider paramProvider,
                                    EpochParamTracker paramTracker,
                                    AdaPotTracker adaPotTracker,
                                    PoolStakeResolver poolStakeResolver,
                                    RewardRestStore rewardRestStore,
                                    String conwayGenesisFilePath) {
        this.db = db;
        this.cfState = cfState;
        this.cfDelta = cfDelta;
        this.governanceStore = governanceStore;
        this.drepDistCalculator = drepDistCalculator;
        this.drepExpiryCalculator = drepExpiryCalculator;
        this.ratificationEngine = ratificationEngine;
        this.enactmentProcessor = enactmentProcessor;
        this.proposalDropService = proposalDropService;
        this.paramProvider = paramProvider;
        this.paramTracker = paramTracker;
        this.adaPotTracker = adaPotTracker;
        this.poolStakeResolver = poolStakeResolver;
        this.rewardRestStore = rewardRestStore;
        this.genesisBootstrap = new com.bloxbean.cardano.yano.ledgerstate.governance.ConwayGenesisBootstrap(
                governanceStore, conwayGenesisFilePath);
    }

    public void setConwayFirstEpoch(int epoch) {
        this.conwayFirstEpoch = epoch;
    }

    public void setSnapshotExporter(com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter exporter) {
        this.snapshotExporter = exporter != null ? exporter
                : com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.NOOP;
    }

    /**
     * Process governance epoch boundary in two phases with a commit between.
     * Phase 1 (Enact): applies previously ratified proposals → commits so committee/params
     *                   changes are visible to subsequent reads.
     * Phase 2 (Ratify + rest): evaluates current proposals, stores new pending, updates DReps.
     * Called from EpochBoundaryProcessor.
     */
    public GovernanceEpochResult processEpochBoundaryAndCommit(int previousEpoch, int newEpoch,
            Map<com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey,
                    BigInteger> utxoBalances,
            Map<String, BigInteger> spendableRewardRest) throws RocksDBException {

        int protocolVersion = resolveProtocolMajor(newEpoch);
        if (protocolVersion < 9) return GovernanceEpochResult.EMPTY;

        // Phase 1: Bootstrap + Enact pending proposals + treasury withdrawal reward_rest
        //          + deposit refunds for enacted/dropped proposals.
        // Committed BEFORE Phase 2 so that:
        //   1. Ratification reads current committee/params/lastEnacted
        //   2. DRep distribution includes treasury withdrawal amounts (via spendableRewardRest)
        // This matches Haskell: ENACT creates reward_rest → committed → DRep dist sees them.
        EnactmentResult enactment;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();
            enactment = processEnactmentPhase(previousEpoch, newEpoch, batch, deltaOps);
            db.write(wo, batch);
        }

        // Read spendable reward_rest AFTER Phase 1 commit.
        // Treasury withdrawal and enacted/dropped proposal refund entries are now in DB.
        // This is passed to DRep distribution so voting power includes these amounts.
        if (spendableRewardRest == null && rewardRestStore != null) {
            spendableRewardRest = rewardRestStore.getSpendableRewardRest(newEpoch);
        }

        // Phase 2: DRep distribution + Ratification + newly expired proposals + donations
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<DeltaOp> deltaOps = new ArrayList<>();
            GovernanceEpochResult result = processRatificationPhase(previousEpoch, newEpoch,
                    enactment, batch, deltaOps, utxoBalances, spendableRewardRest);
            db.write(wo, batch);
            return result;
        }
    }

    /**
     * Phase 1: Bootstrap Conway genesis + enact previously ratified proposals + create reward_rest
     * for treasury withdrawals and deposit refunds (enacted + dropped proposals).
     * <p>
     * Must be committed BEFORE Phase 2 so that:
     * <ol>
     *   <li>Ratification reads current committee/params/lastEnacted</li>
     *   <li>DRep distribution includes treasury withdrawal reward_rest via spendableRewardRest</li>
     * </ol>
     * This matches Haskell NEWEPOCH: ENACT creates reward_rest → committed → DRep dist sees them.
     */
    private EnactmentResult processEnactmentPhase(int previousEpoch, int newEpoch,
                                                   WriteBatch batch, List<DeltaOp> deltaOps)
            throws RocksDBException {

        // Bootstrap Conway genesis on first Conway epoch only (committee, constitution, params).
        // Detect restart: if the previous epoch was already Conway, genesis was already done.
        // This survives JVM restarts because protocol versions are persisted in EpochParamTracker.
        if (!genesisBootstrapped) {
            int prevProtoMajor = resolveProtocolMajor(newEpoch - 1);
            if (prevProtoMajor >= 9) {
                // Previous epoch was Conway — genesis already bootstrapped in a prior run
                log.info("Conway genesis already bootstrapped (previous epoch proto={}, era already Conway), skipping",
                        prevProtoMajor);
                genesisBootstrapped = true;
            } else {
                // This is the first Conway epoch — bootstrap genesis
                if (conwayFirstEpoch < 0) {
                    conwayFirstEpoch = newEpoch;
                }
                genesisBootstrap.bootstrap(conwayFirstEpoch, batch, deltaOps);
                // Persist conwayFirstEpoch to RocksDB
                governanceStore.storeEraFirstEpoch(9, conwayFirstEpoch, batch, deltaOps);
                genesisBootstrapped = true;
            }
        }

        // 1. Enact pending proposals (ratified at previous epoch boundary)
        BigInteger treasuryDelta = BigInteger.ZERO;
        List<GovActionId> pendingEnactmentIds = governanceStore.getPendingEnactments();
        List<GovActionId> pendingDropIds = governanceStore.getPendingDrops();
        Map<GovActionId, GovActionRecord> allProposals = governanceStore.getAllActiveProposals();

        if (!pendingEnactmentIds.isEmpty()) {
            log.info("Phase 1 enact: {} pending enactments at {} → {}",
                    pendingEnactmentIds.size(), previousEpoch, newEpoch);
        }

        for (GovActionId id : pendingEnactmentIds) {
            GovActionRecord proposal = allProposals.get(id);
            if (proposal != null) {
                BigInteger delta = enactmentProcessor.enact(id, proposal, newEpoch, batch, deltaOps);
                treasuryDelta = treasuryDelta.add(delta);
                log.info("Phase 1 enacted: {}/{} type={}", id.getTransactionId().substring(0, 8),
                        id.getGov_action_index(), proposal.actionType());
            }
        }

        // 2. Store treasury withdrawal amounts as reward_rest (for enacted TreasuryWithdrawalsAction).
        //    Created here (Phase 1) so DRep distribution in Phase 2 includes them via spendableRewardRest.
        if (rewardRestStore != null) {
            Map<String, BigInteger> aggregatedWithdrawals = new java.util.HashMap<>();
            for (GovActionId id : pendingEnactmentIds) {
                GovActionRecord enactedProposal = allProposals.get(id);
                if (enactedProposal != null && enactedProposal.govAction()
                        instanceof com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction twa) {
                    if (twa.getWithdrawals() != null) {
                        for (var entry : twa.getWithdrawals().entrySet()) {
                            aggregatedWithdrawals.merge(entry.getKey(), entry.getValue(), BigInteger::add);
                        }
                    }
                }
            }
            for (var entry : aggregatedWithdrawals.entrySet()) {
                boolean stored = rewardRestStore.storeRewardRest(
                        newEpoch,
                        com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore.REWARD_REST_TREASURY_WITHDRAWAL,
                        entry.getKey(), entry.getValue(), previousEpoch, 0,
                        batch, deltaOps);
                if (!stored) {
                    treasuryDelta = treasuryDelta.add(entry.getValue());
                    log.info("Treasury withdrawal to {} unclaimed, returned to treasury",
                            entry.getKey().substring(0, Math.min(16, entry.getKey().length())));
                }
            }
        }

        // 3. Deposit refunds for enacted and dropped proposals.
        //    Created here (Phase 1) so DRep distribution includes proposal deposit refund amounts.
        BigInteger depositRefunds = BigInteger.ZERO;
        Map<String, BigInteger> aggregatedRefunds = new java.util.HashMap<>();

        // 3a. Enacted proposals: refund deposit + remove
        for (GovActionId id : pendingEnactmentIds) {
            GovActionRecord proposal = allProposals.get(id);
            if (proposal != null) {
                BigInteger deposit = proposal.deposit();
                depositRefunds = depositRefunds.add(deposit);
                if (deposit.signum() > 0) {
                    aggregatedRefunds.merge(proposal.returnAddress(), deposit, BigInteger::add);
                }
                governanceStore.removeProposal(id, batch, deltaOps);
                governanceStore.removeVotesForProposal(id.getTransactionId(),
                        id.getGov_action_index(), batch, deltaOps);
            }
        }

        // 3b. Dropped proposals (previously expired → now drop): refund deposit + remove
        for (GovActionId id : pendingDropIds) {
            GovActionRecord proposal = allProposals.get(id);
            if (proposal != null) {
                BigInteger deposit = proposal.deposit();
                depositRefunds = depositRefunds.add(deposit);
                if (deposit.signum() > 0) {
                    aggregatedRefunds.merge(proposal.returnAddress(), deposit, BigInteger::add);
                }
                governanceStore.removeProposal(id, batch, deltaOps);
                governanceStore.removeVotesForProposal(id.getTransactionId(),
                        id.getGov_action_index(), batch, deltaOps);
            }
        }

        // Clear processed pending enactments/drops
        governanceStore.clearPending(batch, deltaOps);

        // Store aggregated refunds as reward_rest
        BigInteger unclaimedRefunds = BigInteger.ZERO;
        if (rewardRestStore != null) {
            for (var entry : aggregatedRefunds.entrySet()) {
                boolean stored = rewardRestStore.storeRewardRest(
                        newEpoch,
                        com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                        entry.getKey(), entry.getValue(), previousEpoch, 0,
                        batch, deltaOps);
                if (!stored) {
                    unclaimedRefunds = unclaimedRefunds.add(entry.getValue());
                }
            }
        }

        if (unclaimedRefunds.signum() > 0) {
            treasuryDelta = treasuryDelta.add(unclaimedRefunds);
            log.info("Unclaimed proposal deposit refunds going to treasury: {}", unclaimedRefunds);
        }

        return new EnactmentResult(treasuryDelta, depositRefunds);
    }

    /**
     * Phase 2: DRep distribution + Ratification + sibling drops + newly expired proposals + donations.
     * Reads committed state (including Phase 1 enactment + reward_rest writes).
     * Treasury withdrawal and enacted/dropped deposit refund reward_rest were already created in Phase 1,
     * so spendableRewardRest includes them for DRep distribution.
     */
    private GovernanceEpochResult processRatificationPhase(int previousEpoch, int newEpoch,
                                                            EnactmentResult enactment,
                                                            WriteBatch batch, List<DeltaOp> deltaOps,
                                                            Map<com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey,
                                                                    BigInteger> utxoBalances,
                                                            Map<String, BigInteger> spendableRewardRest)
            throws RocksDBException {
        long start = System.currentTimeMillis();
        int protocolVersion = resolveProtocolMajor(newEpoch);
        boolean isBootstrapPhase = protocolVersion < 10;

        log.info("Governance epoch boundary {} → {} (protocolVersion={}, bootstrap={})",
                previousEpoch, newEpoch, protocolVersion, isBootstrapPhase);

        BigInteger treasuryDelta = enactment.treasuryDelta();
        BigInteger depositRefunds = enactment.depositRefunds();

        // 1. Calculate DRep distribution.
        // spendableRewardRest includes treasury withdrawal + deposit refund amounts from Phase 1.
        Map<DRepDistKey, BigInteger> drepDist =
                drepDistCalculator.calculate(previousEpoch, utxoBalances, spendableRewardRest);

        // Store DRep distribution snapshot (skip virtual DReps — they have synthetic non-hex hashes)
        for (var entry : drepDist.entrySet()) {
            DRepDistKey dk = entry.getKey();
            if (dk.drepType() <= 1) {
                governanceStore.storeDRepDistEntry(newEpoch, dk.drepType(),
                        dk.drepHash(), entry.getValue(), batch);
            }
        }

        // Export DRep distribution snapshot with expiry info for debugging
        if (snapshotExporter != com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.NOOP) {
            int numDormant = governanceStore.getNumDormantEpochs();
            var allDRepStates = governanceStore.getAllDRepStates();
            var exportEntries = drepDist.entrySet().stream()
                    .map(e -> {
                        DRepDistKey dk = e.getKey();
                        // Virtual DReps (ABSTAIN/NO_CONFIDENCE): no expiry record, not expiry-gated.
                        // Use -1 for expiry fields (N/A), active=true (not subject to expiry checks).
                        if (dk.drepType() > 1) {
                            return new com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.DRepDistEntry(
                                    dk.drepType(), dk.drepHash(), e.getValue(), -1, -1, -1, true);
                        }
                        DRepStateRecord state = allDRepStates.get(new CredentialKey(dk.drepType(), dk.drepHash()));
                        int storedExpiry = (state != null) ? state.expiryEpoch() : -1;
                        int effectiveExpiry = storedExpiry + numDormant;
                        boolean active = effectiveExpiry >= newEpoch - 1; // Haskell: reCurrentEpoch = eNo - 1
                        return new com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.DRepDistEntry(
                                dk.drepType(), dk.drepHash(), e.getValue(),
                                storedExpiry, numDormant, effectiveExpiry, active);
                    })
                    .toList();
            snapshotExporter.exportDRepDistribution(newEpoch, exportEntries);
        }

        // Build set of ACTIVE (non-expired) DRep keys for ratification tally.
        Set<DRepDistKey> activeDRepKeys = buildActiveDRepKeys(drepDist, newEpoch);

        // 2. Get active proposals (enacted/dropped proposals already removed in Phase 1)
        Map<GovActionId, GovActionRecord> activeProposals = governanceStore.getAllActiveProposals();

        // 3. Ratify — reads committed state (committee/params/lastEnacted updated by Phase 1).
        Map<CredentialKey, CommitteeMemberRecord> committeeMembers = governanceStore.getAllCommitteeMembers();
        BigDecimal committeeThreshold = resolveCommitteeThreshold();
        String committeeState = resolveCommitteeState(committeeMembers, newEpoch);
        Map<GovActionType, GovActionId> lastEnactedActions = resolveLastEnactedActions();
        Map<GovActionType, BigDecimal> drepThresholds = resolveDRepThresholds(isBootstrapPhase, newEpoch);
        Map<GovActionType, BigDecimal> spoThresholds = resolveSPOThresholds(newEpoch);
        int committeeMinSize = paramProvider.getCommitteeMinSize(newEpoch);
        int committeeMaxTermLength = paramProvider.getCommitteeMaxTermLength(newEpoch);

        PoolStakeData poolData = PoolStakeData.EMPTY;
        if (poolStakeResolver != null) {
            poolData = poolStakeResolver.resolvePoolStake(previousEpoch);
            if (poolData == null) poolData = PoolStakeData.EMPTY;
        }
        Map<String, BigInteger> poolStakeDist = poolData.poolStakes();
        Map<String, Integer> poolDRepDelegation = poolData.poolDRepDelegations();

        BigInteger treasury = BigInteger.ZERO;
        if (adaPotTracker != null && adaPotTracker.isEnabled()) {
            var prevPot = adaPotTracker.getAdaPot(previousEpoch);
            if (prevPot.isPresent()) {
                treasury = prevPot.get().treasury();
            }
        }

        List<RatificationResult> results = ratificationEngine.evaluateAll(
                activeProposals, drepDist, activeDRepKeys, poolStakeDist, poolDRepDelegation,
                committeeMembers, committeeThreshold, lastEnactedActions,
                newEpoch, isBootstrapPhase, committeeMinSize, committeeMaxTermLength,
                committeeState, treasury, drepThresholds, spoThresholds);

        // Store NEW ratified proposals as pending for next boundary
        for (RatificationResult result : results) {
            if (result.isRatified()) {
                governanceStore.storePendingEnactment(result.govActionId(), batch, deltaOps);
                log.info("Phase 2: ratified → pending enactment {}/{} at {} → {}",
                        result.govActionId().getTransactionId().substring(0, 8),
                        result.govActionId().getGov_action_index(), previousEpoch, newEpoch);
            }
        }

        // Export proposal status for debugging
        if (snapshotExporter != com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.NOOP) {
            var statusEntries = results.stream()
                    .map(r -> new com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter.ProposalStatusEntry(
                            r.govActionId().getTransactionId(), r.govActionId().getGov_action_index(),
                            r.proposal().actionType().name(), r.status().name(),
                            r.proposal().deposit(), r.proposal().returnAddress(),
                            r.proposal().proposedInEpoch(), r.proposal().expiresAfterEpoch()))
                    .toList();
            snapshotExporter.exportProposalStatus(newEpoch, statusEntries);
        }

        // 4. Drop siblings/descendants of enacted proposals + handle newly expired proposals
        Set<GovActionId> proposalsToDrop = proposalDropService.computeProposalsToDrop(results, activeProposals);
        Map<String, BigInteger> siblingRefunds = new java.util.HashMap<>();

        for (GovActionId dropId : proposalsToDrop) {
            GovActionRecord dropped = activeProposals.get(dropId);
            if (dropped != null) {
                BigInteger deposit = dropped.deposit();
                depositRefunds = depositRefunds.add(deposit);
                if (deposit.signum() > 0) {
                    siblingRefunds.merge(dropped.returnAddress(), deposit, BigInteger::add);
                }
                governanceStore.removeProposal(dropId, batch, deltaOps);
                governanceStore.removeVotesForProposal(dropId.getTransactionId(),
                        dropId.getGov_action_index(), batch, deltaOps);
            }
        }

        // Store NEWLY expired proposals as pending drops for next boundary
        for (RatificationResult result : results) {
            if (result.isExpired()) {
                governanceStore.storePendingDrop(result.govActionId(), batch, deltaOps);
            }
        }

        // Store sibling drop refunds as reward_rest (these are new in Phase 2)
        BigInteger unclaimedRefunds = BigInteger.ZERO;
        if (rewardRestStore != null && !siblingRefunds.isEmpty()) {
            for (var entry : siblingRefunds.entrySet()) {
                boolean stored = rewardRestStore.storeRewardRest(
                        newEpoch,
                        com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                        entry.getKey(), entry.getValue(), previousEpoch, 0,
                        batch, deltaOps);
                if (!stored) {
                    unclaimedRefunds = unclaimedRefunds.add(entry.getValue());
                }
            }
        }

        if (unclaimedRefunds.signum() > 0) {
            treasuryDelta = treasuryDelta.add(unclaimedRefunds);
            log.info("Unclaimed sibling drop deposit refunds going to treasury: {}", unclaimedRefunds);
        }

        // 5. Dormant epoch tracking (needed before DRep expiry calculation)
        int remainingCount = activeProposals.size();
        for (RatificationResult result : results) {
            if (result.isRatified() || result.isExpired()) {
                if (activeProposals.containsKey(result.govActionId())) remainingCount--;
            }
        }
        boolean epochHadActiveProposals = remainingCount > 0;
        governanceStore.storeEpochHadActiveProposals(newEpoch, epochHadActiveProposals, batch, deltaOps);

        Set<Integer> dormantEpochs = governanceStore.getDormantEpochs();
        if (!epochHadActiveProposals) {
            dormantEpochs.add(newEpoch);
        }
        if (conwayFirstEpoch >= 0 && newEpoch == conwayFirstEpoch && !dormantEpochs.contains(newEpoch)) {
            dormantEpochs.add(newEpoch);
        }
        governanceStore.storeDormantEpochs(dormantEpochs, batch, deltaOps);

        // 6. Update DRep expiry (after dormant tracking so dormant set is current)
        updateDRepExpiry(newEpoch, epochHadActiveProposals, batch, deltaOps);

        // 7. Process epoch donations
        BigInteger donations = governanceStore.getEpochDonations(previousEpoch);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Governance epoch boundary complete ({} → {}) in {}ms: {} ratified, {} expired, " +
                        "{} dropped, depositRefunds={}, donations={}, dormant={}",
                previousEpoch, newEpoch, elapsed,
                results.stream().filter(RatificationResult::isRatified).count(),
                results.stream().filter(RatificationResult::isExpired).count(),
                proposalsToDrop.size(), depositRefunds, donations, !epochHadActiveProposals);

        return new GovernanceEpochResult(treasuryDelta, depositRefunds, donations);
    }

    // ===== Active DRep Keys =====

    /**
     * Build set of ACTIVE (non-expired) DRep keys from the distribution using stored expiry + pending counter.
     * Only DReps in the distribution with delegated stake are included.
     * Effective expiry = stored expiry + numDormantEpochs (pending counter).
     * Haskell: active when {@code drepExpiry >= reCurrentEpoch} where {@code reCurrentEpoch = eNo - 1}.
     */
    private Set<DRepDistKey> buildActiveDRepKeys(Map<DRepDistKey, BigInteger> drepDist, int newEpoch) {
        Set<DRepDistKey> activeDRepKeys = new java.util.HashSet<>();
        try {
            int numDormant = governanceStore.getNumDormantEpochs();
            var allDRepStates = governanceStore.getAllDRepStates();
            for (var distKey : drepDist.keySet()) {
                if (distKey.drepType() > 1) continue; // Skip virtual DReps (abstain, no_confidence)
                CredentialKey ck = new CredentialKey(distKey.drepType(), distKey.drepHash());
                DRepStateRecord rec = allDRepStates.get(ck);
                if (rec == null) continue;
                int effectiveExpiry = rec.expiryEpoch() + numDormant;
                if (effectiveExpiry >= newEpoch - 1) { // Haskell: reCurrentEpoch = eNo - 1
                    activeDRepKeys.add(distKey);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build active DRep set: {}", e.getMessage());
        }
        return activeDRepKeys;
    }

    // ===== DRep Expiry Update (Haskell Flush Semantics) =====

    /**
     * Update DRep expiry using Haskell's incremental counter approach.
     * At epoch boundaries with active proposals, flush the counter to all stored expiries.
     * At dormant epochs (no active proposals), increment the counter.
     */
    private void updateDRepExpiry(int newEpoch, boolean epochHadActiveProposals,
                                   WriteBatch batch, List<DeltaOp> deltaOps)
            throws RocksDBException {
        int numDormant = governanceStore.getNumDormantEpochs();

        if (epochHadActiveProposals && numDormant > 0) {
            // Flush: add numDormant to every registered DRep stored expiry.
            // Haskell bumps ALL DReps unconditionally (Certs.hs updateDormantDRepExpiries).
            // No guard — even expired DReps accumulate dormant bumps.
            Map<CredentialKey, DRepStateRecord> allDReps = governanceStore.getAllDRepStates();
            for (var entry : allDReps.entrySet()) {
                CredentialKey ck = entry.getKey();
                DRepStateRecord state = entry.getValue();
                // Skip deregistered tombstone records; flush applies to currently registered DReps.
                // (Haskell removes deregistered DReps from vsDReps; Yano keeps tombstones.)
                Long prevDeregSlot = state.previousDeregistrationSlot();
                if (prevDeregSlot != null && state.registeredAtSlot() <= prevDeregSlot) {
                    continue;
                }
                int newExpiry = state.expiryEpoch() + numDormant;
                boolean active = newExpiry >= newEpoch - 1; // Haskell: reCurrentEpoch = eNo - 1
                if (newExpiry != state.expiryEpoch() || active != state.active()) {
                    DRepStateRecord updated = state.withExpiry(newExpiry, active);
                    governanceStore.storeDRepState(ck.credType(), ck.hash(), updated, batch, deltaOps);
                }
            }
            governanceStore.storeNumDormantEpochs(0, batch, deltaOps);

        } else if (!epochHadActiveProposals) {
            // Dormant epoch: increment counter
            governanceStore.storeNumDormantEpochs(numDormant + 1, batch, deltaOps);
        }
        // If epochHadActiveProposals && numDormant == 0: nothing to do (no change)
    }

    /**
     * Find the latest governance proposal submitted at or before the given slot.
     * Uses permanent PREFIX_PROPOSAL_SUBMISSION store (survives proposal removal).
     */
    private DRepExpiryCalculator.ProposalSubmissionInfo findLatestProposalUpToSlot(long maxSlot) {
        try {
            return governanceStore.findLatestProposalUpToSlot(maxSlot);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Resolution Helpers =====

    private int resolveProtocolMajor(int epoch) {
        if (paramTracker != null && paramTracker.isEnabled()) {
            return paramTracker.getProtocolMajor(epoch);
        }
        return paramProvider.getProtocolMajor(epoch);
    }

    private BigDecimal resolveCommitteeThreshold() throws RocksDBException {
        var threshold = governanceStore.getCommitteeThreshold();
        if (threshold.isPresent()) {
            var t = threshold.get();
            if (t.denominator().signum() > 0) {
                return new BigDecimal(t.numerator()).divide(
                        new BigDecimal(t.denominator()), java.math.MathContext.DECIMAL128);
            }
        }
        return new BigDecimal("0.667"); // default 2/3
    }

    private String resolveCommitteeState(Map<CredentialKey, CommitteeMemberRecord> members,
                                         int epoch) {
        // If no members exist at all, treat as NO_CONFIDENCE
        if (members.isEmpty()) return "NO_CONFIDENCE";
        // Check if any non-expired, non-resigned members exist
        boolean hasActive = members.values().stream()
                .anyMatch(m -> !m.resigned() && m.expiryEpoch() > epoch);
        return hasActive ? "NORMAL" : "NO_CONFIDENCE";
    }

    private Map<GovActionType, GovActionId> resolveLastEnactedActions() throws RocksDBException {
        Map<GovActionType, GovActionId> result = new HashMap<>();
        for (GovActionType type : GovActionType.values()) {
            var last = governanceStore.getLastEnactedAction(type);
            if (last.isPresent()) {
                result.put(type, new GovActionId(last.get().txHash(), last.get().govActionIndex()));
            }
        }
        return result;
    }

    private Map<GovActionType, BigDecimal> resolveDRepThresholds(boolean isBootstrapPhase, int epoch) {
        Map<GovActionType, BigDecimal> thresholds = new HashMap<>();
        if (isBootstrapPhase) {
            for (GovActionType type : GovActionType.values()) {
                thresholds.put(type, BigDecimal.ZERO);
            }
            return thresholds;
        }

        // Read from protocol params (paramTracker or genesis defaults)
        ProtocolParamUpdate params = (paramTracker instanceof EpochParamTracker ept)
                ? ept.getResolvedParams(epoch) : null;
        if (params != null && params.getDrepVotingThresholds() != null) {
            var dt = params.getDrepVotingThresholds();
            thresholds.put(GovActionType.NO_CONFIDENCE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtMotionNoConfidence()));
            thresholds.put(GovActionType.UPDATE_COMMITTEE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtCommitteeNormal()));
            thresholds.put(GovActionType.NEW_CONSTITUTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtUpdateToConstitution()));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtHardForkInitiation()));
            thresholds.put(GovActionType.TREASURY_WITHDRAWALS_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtTreasuryWithdrawal()));
            // ParameterChange: per-proposal threshold computed in RatificationEngine
            // Use max possible (governance group = 0.75) as default; actual is computed per proposal
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(dt.getDvtPPGovGroup()));
        } else {
            // Fallback defaults
            thresholds.put(GovActionType.NO_CONFIDENCE, new BigDecimal("0.67"));
            thresholds.put(GovActionType.UPDATE_COMMITTEE, new BigDecimal("0.67"));
            thresholds.put(GovActionType.NEW_CONSTITUTION, new BigDecimal("0.75"));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION, new BigDecimal("0.60"));
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION, new BigDecimal("0.67"));
            thresholds.put(GovActionType.TREASURY_WITHDRAWALS_ACTION, new BigDecimal("0.67"));
        }
        return thresholds;
    }

    private Map<GovActionType, BigDecimal> resolveSPOThresholds(int epoch) {
        Map<GovActionType, BigDecimal> thresholds = new HashMap<>();
        ProtocolParamUpdate params = (paramTracker instanceof EpochParamTracker ept)
                ? ept.getResolvedParams(epoch) : null;
        if (params != null && params.getPoolVotingThresholds() != null) {
            var pt = params.getPoolVotingThresholds();
            thresholds.put(GovActionType.NO_CONFIDENCE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtMotionNoConfidence()));
            thresholds.put(GovActionType.UPDATE_COMMITTEE,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtCommitteeNormal()));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtHardForkInitiation()));
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION,
                    ProtocolParamGroupClassifier.ratioToBigDecimal(pt.getPvtPPSecurityGroup()));
        } else {
            thresholds.put(GovActionType.NO_CONFIDENCE, new BigDecimal("0.60"));
            thresholds.put(GovActionType.UPDATE_COMMITTEE, new BigDecimal("0.51"));
            thresholds.put(GovActionType.HARD_FORK_INITIATION_ACTION, new BigDecimal("0.51"));
            thresholds.put(GovActionType.PARAMETER_CHANGE_ACTION, new BigDecimal("0.51"));
        }
        return thresholds;
    }

    // ===== Result =====

    /**
     * Result of governance epoch boundary processing.
     *
     * @param treasuryDelta  Net change to treasury (negative = withdrawals, positive = donations)
     * @param depositRefunds Total proposal deposits refunded
     * @param donations      Total donations in the previous epoch
     */
    public record GovernanceEpochResult(BigInteger treasuryDelta, BigInteger depositRefunds,
                                        BigInteger donations) {
        public static final GovernanceEpochResult EMPTY =
                new GovernanceEpochResult(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }
}
