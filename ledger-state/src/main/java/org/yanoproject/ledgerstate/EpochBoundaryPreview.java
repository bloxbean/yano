package org.yanoproject.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee;
import org.rocksdb.RocksDBException;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.era.EraProvider;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.ledgerstate.DefaultAccountStateStore.RewardRestCredential;
import org.yanoproject.ledgerstate.LedgerStateSnapshotReader.RewardRestEntry;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec.CommitteeThreshold;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec.ConstitutionRecord;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import org.yanoproject.ledgerstate.governance.epoch.GovernanceEpochProcessor;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.yanoproject.ledgerstate.governance.ratification.EnactmentProcessor;
import org.yanoproject.ledgerstate.governance.ratification.EnactmentProcessor.CommitteeAddition;
import org.yanoproject.ledgerstate.governance.ratification.EnactmentProcessor.CommitteeUpdate;
import org.yanoproject.ledgerstate.governance.ratification.ProposalDropService;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Side-effect-free dry run of the validation-visible effects of the next epoch boundary
 * ({@code newEpoch - 1 → newEpoch}), computed over one RocksDB read snapshot (ADR-056 §3,
 * {@code TickedLedgerView}). Nothing is written and no live store or in-memory tracker is read:
 * RocksDB reads go through the snapshot's {@code ReadOptions}, and in-memory tracker state comes
 * from {@link Inputs}, captured together with the snapshot.
 *
 * <p>Each value mirrors the real boundary path and reuses its code:</p>
 * <ul>
 *   <li><b>Protocol parameters</b> — {@code EpochBoundaryProcessor} step 1
 *       ({@link EpochParamTracker#finalizeEpoch}) then governance Phase 1 enactments
 *       ({@link EpochParamTracker#applyEnactedParamChange}), via
 *       {@link EpochParamTracker#previewEpochParams}, and the snapshot is built by
 *       {@link DefaultAccountStateStore#protocolParamsSnapshot} like the canonical view's.</li>
 *   <li><b>POOLREAP</b> — {@code PoolReapProcessor}'s own plan builder, reading through the
 *       snapshot: retired pools and their deposit refunds (registered reward accounts) or unclaimed
 *       deposits (treasury).</li>
 *   <li><b>Governance Phase 1</b> ({@code GovernanceEpochProcessor.processEnactmentPhase}):
 *       enactments in pending order ({@link EnactmentProcessor}'s pure effect functions), removals
 *       ({@link GovernanceEpochProcessor#planProposalRemovals} and
 *       {@link GovernanceEpochProcessor#claimForRemoval}), treasury withdrawals
 *       ({@link GovernanceEpochProcessor#aggregateTreasuryWithdrawals}) and the reward_rest entries
 *       {@link DefaultAccountStateStore#storeRewardRest} would write, with the same credential parsing
 *       ({@link DefaultAccountStateStore#rewardRestCredential}) and registration rule.</li>
 * </ul>
 *
 * <p>The write semantics of the real Phase 1 batch are reproduced exactly: reads inside the batch see
 * committed (pre-boundary) state, not earlier writes of the same batch. So a committee removal or
 * NoConfidence clear only deletes members that exist before the boundary, an UpdateCommittee addition
 * keeps the hot key of the <em>pre-boundary</em> record, and a reward_rest write is
 * {@code committed amount + new amount} with the last write per key winning.</p>
 *
 * <p>Not computed (callers must fail closed): reward balances of the completed epoch, treasury and
 * reserves, the DRep distribution, ratification of the next pending set, DRep expiry and the dormant
 * epoch counter (not read by any Conway transaction rule), and the stake snapshot.</p>
 */
public final class EpochBoundaryPreview {

    /**
     * In-memory inputs captured together with the RocksDB snapshot (under the canonical gate's read
     * lock), so they describe the same ledger tip. Create with
     * {@link DefaultAccountStateStore#captureBoundaryPreviewInputs(int)}.
     */
    public static final class Inputs {
        private final int newEpoch;
        private final String unavailableReason;
        private final EpochParamTracker tracker;
        private final EpochParamTracker.PreviewBase paramsBase;
        private final EpochParamProvider staticParams;
        private final boolean governanceProcessing;
        private final EpochParamTracker.CapturedEras governanceEras;
        private final int previousProtocolMajor;
        private final boolean poolReapRefundsEnabled;

        /**
         * @param governanceProcessing  governance epoch processing is wired
         * @param governanceEras        the governance processor's era answers, captured now; {@code null}
         *                              when it has no era provider
         * @param previousProtocolMajor protocol major of {@code newEpoch - 1}
         */
        Inputs(int newEpoch, String unavailableReason, EpochParamTracker tracker,
               EpochParamTracker.PreviewBase paramsBase, EpochParamProvider staticParams,
               boolean governanceProcessing, EpochParamTracker.CapturedEras governanceEras,
               int previousProtocolMajor, boolean poolReapRefundsEnabled) {
            this.newEpoch = newEpoch;
            this.unavailableReason = unavailableReason;
            this.tracker = tracker;
            this.paramsBase = paramsBase;
            this.staticParams = staticParams;
            this.governanceProcessing = governanceProcessing;
            this.governanceEras = governanceEras;
            this.previousProtocolMajor = previousProtocolMajor;
            this.poolReapRefundsEnabled = poolReapRefundsEnabled;
        }

        static Inputs unavailable(int newEpoch, String reason) {
            return new Inputs(newEpoch, Objects.requireNonNull(reason, "reason"), null, null, null, false, null,
                    -1, false);
        }

        /**
         * Captures the inputs, reading the governance processor's era provider now (its answers must
         * belong to the same tip as the snapshot).
         */
        static Inputs capture(int newEpoch, EpochParamTracker tracker, EpochParamProvider staticParams,
                              GovernanceEpochProcessor governanceProcessor, boolean poolReapRefundsEnabled) {
            boolean tracked = tracker != null && tracker.isEnabled();
            EpochParamTracker.PreviewBase paramsBase = tracked ? tracker.capturePreviewBase(newEpoch) : null;
            EraProvider eras = governanceProcessor != null ? governanceProcessor.eraProvider() : null;
            int previousMajor = tracked
                    ? tracker.getProtocolMajor(newEpoch - 1)
                    : staticParams.getProtocolMajor(newEpoch - 1);
            return new Inputs(newEpoch, null, tracker, paramsBase, staticParams, governanceProcessor != null,
                    eras != null ? EpochParamTracker.CapturedEras.capture(eras, newEpoch) : null,
                    previousMajor, poolReapRefundsEnabled);
        }

        /** @return the epoch the previewed boundary enters */
        public int newEpoch() {
            return newEpoch;
        }

        /** @return why no preview can be computed at all, or {@code null} */
        public String unavailableReason() {
            return unavailableReason;
        }
    }

    /**
     * Governance Phase 1 effects.
     *
     * @param enacted             pending enactments that exist, in enactment order
     * @param removedProposals    proposals removed (enacted, expired, siblings and descendants)
     * @param newRoots            purpose root key → last enacted action, for purposes this boundary
     *                            changes ({@code UPDATE_COMMITTEE} for both committee actions)
     * @param committeeMembers    governance committee records after enactment (all of them)
     * @param committeeThreshold  quorum after enactment
     * @param committeePresent    false after a NoConfidence enactment
     * @param constitution        constitution after enactment
     * @param enactedParamUpdates parameter updates applied, in order
     * @param hardFork            true when a HardForkInitiation is enacted
     * @param rewardRestWrites    reward_rest entries written (key → stored total), last write per key
     * @param treasuryDelta       Phase 1 treasury delta: minus enacted withdrawals, plus unclaimed
     *                            withdrawals and unclaimed refunds
     * @param unclaimedToTreasury withdrawals and refunds whose reward account is not registered
     * @param prunedCommitteeColds cold credentials whose committee state (record, certificate-path hot key
     *                            and resignation) the boundary drops ({@link CommitteeStatePruning}); already
     *                            absent from {@code committeeMembers}
     */
    public record GovernanceEffects(List<GovActionId> enacted, Set<GovActionId> removedProposals,
                                    Map<GovActionType, GovActionId> newRoots,
                                    Map<CredentialKey, CommitteeMemberRecord> committeeMembers,
                                    Optional<CommitteeThreshold> committeeThreshold, boolean committeePresent,
                                    Optional<ConstitutionRecord> constitution,
                                    List<ProtocolParamUpdate> enactedParamUpdates, boolean hardFork,
                                    Map<RewardRestKey, BigInteger> rewardRestWrites,
                                    BigInteger treasuryDelta, BigInteger unclaimedToTreasury,
                                    Set<CredentialKey> prunedCommitteeColds) {
    }

    /** Key of a reward_rest entry. */
    public record RewardRestKey(int spendableEpoch, byte type, int credType, String credHash) {
        public RewardRestKey {
            credHash = credHash.toLowerCase(Locale.ROOT);
        }

        /** @return {@code credType:credHash} */
        public String credential() {
            return credType + ":" + credHash;
        }
    }

    private final int newEpoch;
    private final String unavailableReason;
    private final ProtocolParamsSnapshot protocolParams;
    private final String protocolParamsUnavailableReason;
    private final Set<String> retiredPools;
    private final Map<String, BigInteger> poolDepositRefunds;
    private final BigInteger unclaimedPoolDeposits;
    private final String poolsUnavailableReason;
    private final GovernanceEffects governance;
    private final String governanceUnavailableReason;
    private final Map<String, BigInteger> rewardRestCredits;

    private EpochBoundaryPreview(Builder b) {
        this.newEpoch = b.newEpoch;
        this.unavailableReason = b.unavailableReason;
        this.protocolParams = b.protocolParams;
        this.protocolParamsUnavailableReason = b.protocolParamsUnavailableReason;
        this.retiredPools = b.retiredPools != null ? Set.copyOf(b.retiredPools) : Set.of();
        this.poolDepositRefunds = b.poolDepositRefunds != null
                ? Collections.unmodifiableMap(new TreeMap<>(b.poolDepositRefunds)) : Map.of();
        this.unclaimedPoolDeposits = b.unclaimedPoolDeposits != null ? b.unclaimedPoolDeposits : BigInteger.ZERO;
        this.poolsUnavailableReason = b.poolsUnavailableReason;
        this.governance = b.governance;
        this.governanceUnavailableReason = b.governanceUnavailableReason;
        this.rewardRestCredits = b.rewardRestCredits != null
                ? Collections.unmodifiableMap(new TreeMap<>(b.rewardRestCredits)) : Map.of();
    }

    /** @return the epoch the boundary enters */
    public int newEpoch() {
        return newEpoch;
    }

    /** @return why nothing could be previewed, or {@code null} */
    public String unavailableReason() {
        return unavailableReason;
    }

    /** @return true when a HardForkInitiation is enacted at this boundary */
    public boolean hardForkEnacted() {
        return governance != null && governance.hardFork();
    }

    /** @return the new epoch's effective parameters; empty when unavailable (see the reason) */
    public Optional<ProtocolParamsSnapshot> protocolParams() {
        return Optional.ofNullable(protocolParams);
    }

    /** @return why {@link #protocolParams()} is empty, or {@code null} */
    public String protocolParamsUnavailableReason() {
        return protocolParamsUnavailableReason;
    }

    /** @return pool ids (hex) that POOLREAP retires */
    public Set<String> retiredPools() {
        return retiredPools;
    }

    /** @return pool deposit refunds per registered reward credential ({@code credType:hash}) */
    public Map<String, BigInteger> poolDepositRefunds() {
        return poolDepositRefunds;
    }

    /** @return deposits of retired pools whose reward account is not registered (to the treasury) */
    public BigInteger unclaimedPoolDeposits() {
        return unclaimedPoolDeposits;
    }

    /** @return why pool effects are unknown, or {@code null} */
    public String poolsUnavailableReason() {
        return poolsUnavailableReason;
    }

    /**
     * @return governance Phase 1 effects; {@code null} when unavailable (see
     *         {@link #governanceUnavailableReason()}). Empty effects when the boundary runs no
     *         governance (protocol version below 9).
     */
    public GovernanceEffects governance() {
        return governance;
    }

    /** @return why {@link #governance()} is {@code null}, or {@code null} */
    public String governanceUnavailableReason() {
        return governanceUnavailableReason;
    }

    /**
     * @return reward_rest credited at this boundary, per registered credential
     *         ({@code credType:hash}): every entry spendable by the new epoch after Phase 1, i.e. the
     *         governance refunds and withdrawals that {@code PostEpochTransition}
     *         ({@code creditAndRemoveSpendableRewardRest}) credits, plus any pre-Conway MIR entries,
     *         which the real boundary credits earlier, in its reward step
     *         ({@code creditMirRewardRest}), not in {@code PostEpochTransition}. MIR entries do not
     *         exist in Conway. Informational only: the ticked view never serves reward balances
     *         (account reads fail closed). Pool deposit refunds are separate
     *         ({@link #poolDepositRefunds()}); rewards of the completed epoch are not included.
     */
    public Map<String, BigInteger> rewardRestCredits() {
        return rewardRestCredits;
    }

    /**
     * Computes the preview.
     *
     * @param ledger reads bound to the snapshot the inputs were captured with
     * @param inputs in-memory inputs captured with the snapshot
     * @throws RocksDBException on read failure (the caller reports the value as unavailable)
     */
    public static EpochBoundaryPreview compute(LedgerStateSnapshotReader ledger, Inputs inputs)
            throws RocksDBException {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(inputs, "inputs");
        Builder result = new Builder(inputs.newEpoch);
        if (inputs.unavailableReason != null) {
            result.unavailableReason = inputs.unavailableReason;
            return result.build();
        }
        int newEpoch = inputs.newEpoch;

        Optional<int[]> boundary = ledger.boundaryState();
        if (boundary.isPresent() && boundary.get()[0] >= newEpoch) {
            result.unavailableReason = "the boundary into epoch " + boundary.get()[0]
                    + " is already recorded at step " + boundary.get()[1] + "; it is not dry-run again";
            return result.build();
        }
        if (boundary.isPresent() && boundary.get()[0] == newEpoch - 1
                && boundary.get()[1] < EpochBoundaryProcessor.STEP_COMPLETE) {
            // The real path re-processes the incomplete boundary first; the state is partial.
            result.unavailableReason = "the boundary into epoch " + (newEpoch - 1) + " is incomplete (step "
                    + boundary.get()[1] + ")";
            return result.build();
        }

        previewPoolReap(ledger, inputs, result);

        // Parameters before governance: EpochBoundaryProcessor step 1 (finalizeEpoch).
        boolean tracked = inputs.tracker != null && inputs.tracker.isEnabled() && inputs.paramsBase != null;
        int protocolMajor = tracked
                ? inputs.tracker.previewView(inputs.paramsBase,
                        inputs.tracker.previewEpochParams(inputs.paramsBase, List.of())).getProtocolMajor(newEpoch)
                : inputs.staticParams.getProtocolMajor(newEpoch);

        List<ProtocolParamUpdate> enactedUpdates = List.of();
        Optional<GovernanceSnapshotReader> governanceReader = ledger.governance();
        String transition = eraTransition(inputs, protocolMajor);
        if (transition != null) {
            // Era overlays, the genesis bootstrap and the rules themselves change here; the era
            // provider learns the new era only when its first block is applied, so none of it is
            // dry-run.
            result.governanceUnavailableReason = transition;
        } else if (governanceReader.isEmpty()) {
            result.governanceUnavailableReason = "governance tracking is disabled";
        } else if (protocolMajor < 9) {
            // GovernanceEpochProcessor.processEpochBoundaryAndCommit returns before Phase 1.
            result.governance = noGovernanceEffects(governanceReader.get());
        } else if (!inputs.governanceProcessing) {
            result.governanceUnavailableReason = "governance epoch processing is not configured";
        } else {
            String bootstrap = pendingGenesisBootstrap(inputs.governanceEras, governanceReader.get(), newEpoch);
            if (bootstrap != null) {
                result.governanceUnavailableReason = bootstrap;
            } else {
                result.governance = previewEnactmentPhase(ledger, governanceReader.get(), newEpoch);
                enactedUpdates = result.governance.enactedParamUpdates();
            }
        }

        if (transition != null) {
            result.protocolParamsUnavailableReason = transition;
        } else if (protocolMajor >= 9 && result.governance == null) {
            result.protocolParamsUnavailableReason = "governance enactments at the boundary into epoch " + newEpoch
                    + " are unknown (" + result.governanceUnavailableReason + ")";
        } else if (tracked) {
            ProtocolParamUpdate resolved = inputs.tracker.previewEpochParams(inputs.paramsBase, enactedUpdates);
            result.protocolParams = DefaultAccountStateStore.protocolParamsSnapshot(
                    inputs.tracker.previewView(inputs.paramsBase, resolved), inputs.staticParams, newEpoch)
                    .orElse(null);
        } else {
            result.protocolParams = DefaultAccountStateStore.protocolParamsSnapshot(
                    null, inputs.staticParams, newEpoch).orElse(null);
        }
        if (result.protocolParams == null && result.protocolParamsUnavailableReason == null) {
            result.protocolParamsUnavailableReason = "no effective protocol parameters for epoch " + newEpoch;
        }

        result.rewardRestCredits = rewardRestCredits(ledger, newEpoch,
                result.governance != null ? result.governance.rewardRestWrites() : Map.of());
        return result.build();
    }

    // ------------------------------------------------------------------ POOLREAP

    private static void previewPoolReap(LedgerStateSnapshotReader ledger, Inputs inputs, Builder result) {
        PoolReapProcessor.PoolReapPlan plan =
                PoolReapProcessor.planFromSnapshot(ledger.db(), ledger.cfState(), ledger.reads(), inputs.newEpoch);
        if (!plan.entries().isEmpty() && !inputs.poolReapRefundsEnabled) {
            result.poolsUnavailableReason = "POOLREAP at the boundary into epoch " + inputs.newEpoch
                    + " needs the reward/refund processor, which is disabled";
            return;
        }
        Map<String, BigInteger> refunds = new HashMap<>();
        BigInteger unclaimed = BigInteger.ZERO;
        for (PoolReapProcessor.PoolReapEntry entry : plan.entries()) {
            if (entry.registeredRewardCredential()) {
                refunds.merge(entry.rewardCredentialType() + ":" + entry.rewardCredentialHash().toLowerCase(Locale.ROOT),
                        entry.deposit(), BigInteger::add);
            } else {
                unclaimed = unclaimed.add(entry.deposit());
            }
        }
        result.retiredPools = plan.retiringPoolHashes();
        result.poolDepositRefunds = refunds;
        result.unclaimedPoolDeposits = unclaimed;
    }

    // ------------------------------------------------------------------ governance Phase 1

    /**
     * @return why this boundary is an era or protocol-version transition that is not dry-run, or
     *         {@code null}:
     *         <ul>
     *           <li>the new epoch's protocol version (before enactments, i.e. after pre-Conway
     *               updates) differs from the previous epoch's;</li>
     *           <li>the new epoch is at protocol version 9 or later but the captured era answers do not
     *               place it in Conway (the Babbage→Conway boundary: the Conway start is recorded only
     *               when the first Conway block is applied).</li>
     *         </ul>
     */
    private static String eraTransition(Inputs inputs, int protocolMajor) {
        int newEpoch = inputs.newEpoch;
        if (inputs.previousProtocolMajor >= 0 && protocolMajor != inputs.previousProtocolMajor) {
            return "the protocol version changes at the boundary into epoch " + newEpoch + " ("
                    + inputs.previousProtocolMajor + " -> " + protocolMajor + "); it is not dry-run";
        }
        EpochParamTracker.CapturedEras eras = inputs.governanceEras != null ? inputs.governanceEras
                : inputs.paramsBase != null ? inputs.paramsBase.eras() : null;
        if (protocolMajor >= 9 && eras != null) {
            Integer conwayEpoch = eras.resolveFirstConwayEpochOrNull();
            if (conwayEpoch == null || conwayEpoch > newEpoch) {
                return "the Conway transition may be at the boundary into epoch " + newEpoch
                        + " (the Conway era is not yet known for it); it is not dry-run";
            }
        }
        return null;
    }

    private static String pendingGenesisBootstrap(EpochParamTracker.CapturedEras eras,
                                                  GovernanceSnapshotReader governance, int newEpoch)
            throws RocksDBException {
        if (eras == null) {
            return null;
        }
        Integer conwayEpoch = eras.resolveFirstConwayEpochOrNull();
        if (conwayEpoch == null || newEpoch < conwayEpoch) {
            return null;
        }
        boolean persisted = governance.conwayFirstEpoch() == conwayEpoch && governance.committeeThreshold().isPresent();
        return persisted ? null
                : "the Conway genesis bootstrap runs at the boundary into epoch " + newEpoch + " and is not dry-run";
    }

    private static GovernanceEffects noGovernanceEffects(GovernanceSnapshotReader governance) throws RocksDBException {
        return new GovernanceEffects(List.of(), Set.of(), Map.of(),
                Collections.unmodifiableMap(new LinkedHashMap<>(governance.committeeMembers())),
                governance.committeeThreshold(), governance.committeePresent(), governance.constitution(),
                List.of(), false, Map.of(), BigInteger.ZERO, BigInteger.ZERO, Set.of());
    }

    private static GovernanceEffects previewEnactmentPhase(LedgerStateSnapshotReader ledger,
                                                           GovernanceSnapshotReader governance, int newEpoch)
            throws RocksDBException {
        int previousEpoch = newEpoch - 1;
        List<GovActionId> pendingEnactmentIds = governance.pendingEnactments();
        List<GovActionId> pendingDropIds = governance.pendingDrops();
        Map<GovActionId, GovActionRecord> allProposals = governance.proposalRecords();

        // Committed (pre-boundary) committee state: every read the real batch makes sees this.
        Map<CredentialKey, CommitteeMemberRecord> committed = new LinkedHashMap<>(governance.committeeMembers());
        Map<CredentialKey, CommitteeMemberRecord> committee = new LinkedHashMap<>(committed);
        Optional<CommitteeThreshold> threshold = governance.committeeThreshold();
        boolean committeePresent = governance.committeePresent();
        Optional<ConstitutionRecord> constitution = governance.constitution();
        Map<GovActionType, GovActionId> roots = new LinkedHashMap<>();
        List<ProtocolParamUpdate> paramUpdates = new ArrayList<>();
        List<GovActionId> enacted = new ArrayList<>();
        boolean hardFork = false;
        BigInteger treasuryDelta = BigInteger.ZERO;

        // 1. Enact pending proposals (EnactmentProcessor.enact, in pending order).
        for (GovActionId id : pendingEnactmentIds) {
            GovActionRecord proposal = allProposals.get(id);
            if (proposal == null) {
                continue;
            }
            enacted.add(id);
            switch (proposal.actionType()) {
                case PARAMETER_CHANGE_ACTION -> {
                    ProtocolParamUpdate update = EnactmentProcessor.enactedParamUpdate(proposal);
                    if (update != null) {
                        paramUpdates.add(update);
                    }
                }
                case HARD_FORK_INITIATION_ACTION -> {
                    hardFork = true;
                    ProtocolParamUpdate update = EnactmentProcessor.enactedParamUpdate(proposal);
                    if (update != null) {
                        paramUpdates.add(update);
                    }
                }
                case TREASURY_WITHDRAWALS_ACTION -> {
                    if (proposal.govAction() instanceof TreasuryWithdrawalsAction twa
                            && twa.getWithdrawals() != null) {
                        for (BigInteger amount : twa.getWithdrawals().values()) {
                            treasuryDelta = treasuryDelta.subtract(amount);
                        }
                    }
                }
                case NO_CONFIDENCE -> {
                    // clearAllCommitteeMembers deletes every member present in committed state.
                    committed.keySet().forEach(committee::remove);
                    committeePresent = false;
                }
                case UPDATE_COMMITTEE -> {
                    if (proposal.govAction() instanceof UpdateCommittee uc) {
                        CommitteeUpdate change = EnactmentProcessor.committeeUpdateOf(uc);
                        for (CredentialKey member : change.removals()) {
                            CredentialKey key = normalize(member);
                            // removeCommitteeMember deletes only a committed record.
                            if (committed.containsKey(key)) {
                                committee.remove(key);
                            }
                        }
                        for (CommitteeAddition addition : change.additions()) {
                            CredentialKey key = normalize(addition.member());
                            committee.put(key, EnactmentProcessor.enactedMemberRecord(
                                    committed.get(key), addition.expiryEpoch()));
                        }
                        if (change.hasThreshold()) {
                            threshold = Optional.of(new CommitteeThreshold(
                                    change.thresholdNumerator(), change.thresholdDenominator()));
                        }
                    }
                    committeePresent = true;
                }
                case NEW_CONSTITUTION -> {
                    ConstitutionRecord record = EnactmentProcessor.enactedConstitution(proposal);
                    if (record != null) {
                        constitution = Optional.of(record);
                    }
                }
                case INFO_ACTION -> {
                    // no effect
                }
            }
            GovActionType purpose = EnactmentProcessor.purposeOf(proposal.actionType());
            if (purpose != null) {
                roots.put(purpose, id);
            }
        }

        // Committee state of non-members (the real boundary prunes it at the start of Phase 2, over the
        // committed Phase 1 result).
        Set<CredentialKey> prunedCommittee = CommitteeStatePruning.coldsToPrune(committee,
                CommitteeStatePruning.certificatePathColds(ledger));
        prunedCommittee.forEach(committee::remove);

        RewardRestWriter writer = new RewardRestWriter(ledger, newEpoch);
        BigInteger unclaimed = BigInteger.ZERO;

        // 2. Treasury withdrawals as reward_rest (unregistered: back to the treasury).
        Map<String, BigInteger> withdrawals =
                GovernanceEpochProcessor.aggregateTreasuryWithdrawals(pendingEnactmentIds, allProposals);
        for (Map.Entry<String, BigInteger> entry : withdrawals.entrySet()) {
            if (!writer.store(DefaultAccountStateStore.REWARD_REST_TREASURY_WITHDRAWAL, entry.getKey(), entry.getValue())) {
                treasuryDelta = treasuryDelta.add(entry.getValue());
                unclaimed = unclaimed.add(entry.getValue());
            }
        }

        // 3. Removals and deposit refunds.
        Set<GovActionId> removed = new LinkedHashSet<>();
        Map<String, BigInteger> refunds = new HashMap<>();
        for (GovernanceEpochProcessor.RemovalStep step : GovernanceEpochProcessor.planProposalRemovals(
                pendingEnactmentIds, pendingDropIds, allProposals, new ProposalDropService())) {
            GovernanceEpochProcessor.claimForRemoval(step.id(), allProposals, removed, refunds);
        }
        BigInteger unclaimedRefunds = BigInteger.ZERO;
        for (Map.Entry<String, BigInteger> entry : refunds.entrySet()) {
            if (!writer.store(DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND, entry.getKey(), entry.getValue())) {
                unclaimedRefunds = unclaimedRefunds.add(entry.getValue());
            }
        }
        if (unclaimedRefunds.signum() > 0) {
            treasuryDelta = treasuryDelta.add(unclaimedRefunds);
            unclaimed = unclaimed.add(unclaimedRefunds);
        }

        return new GovernanceEffects(List.copyOf(enacted), Collections.unmodifiableSet(removed),
                Collections.unmodifiableMap(roots), Collections.unmodifiableMap(committee), threshold,
                committeePresent, constitution, List.copyOf(paramUpdates), hardFork,
                Collections.unmodifiableMap(writer.writes), treasuryDelta, unclaimed,
                Collections.unmodifiableSet(prunedCommittee));
    }

    private static CredentialKey normalize(CredentialKey key) {
        return new CredentialKey(key.credType(), key.hash().toLowerCase(Locale.ROOT));
    }

    /** {@link DefaultAccountStateStore#storeRewardRest} over the snapshot, collecting the writes. */
    private static final class RewardRestWriter {
        private final LedgerStateSnapshotReader ledger;
        private final int spendableEpoch;
        private final Map<RewardRestKey, BigInteger> writes = new LinkedHashMap<>();

        RewardRestWriter(LedgerStateSnapshotReader ledger, int spendableEpoch) {
            this.ledger = ledger;
            this.spendableEpoch = spendableEpoch;
        }

        boolean store(byte type, String rewardAccountHex, BigInteger amount) throws RocksDBException {
            RewardRestCredential credential = DefaultAccountStateStore.rewardRestCredential(rewardAccountHex, amount);
            if (credential == null) {
                return false;
            }
            if (ledger.stakeAccount(credential.credType(), credential.credHash()).isEmpty()) {
                return false;
            }
            // storeRewardRest adds to the committed entry (never to an earlier write of the batch).
            BigInteger existing = ledger.rewardRestAmount(spendableEpoch, type, credential.credType(),
                    credential.credHash()).orElse(BigInteger.ZERO);
            RewardRestKey key = new RewardRestKey(spendableEpoch, type, credential.credType(), credential.credHash());
            writes.remove(key);
            writes.put(key, existing.add(amount));
            return true;
        }
    }

    // ------------------------------------------------------------------ reward_rest credit

    /**
     * Every reward_rest entry spendable by the new epoch after Phase 1 (committed entries overlaid
     * with Phase 1 writes), summed per credential, for registered credentials only; see
     * {@link #rewardRestCredits()} for which boundary step credits which type.
     */
    private static Map<String, BigInteger> rewardRestCredits(LedgerStateSnapshotReader ledger, int newEpoch,
                                                             Map<RewardRestKey, BigInteger> writes)
            throws RocksDBException {
        Map<RewardRestKey, BigInteger> entries = new LinkedHashMap<>();
        for (RewardRestEntry entry : ledger.rewardRest(newEpoch)) {
            entries.put(new RewardRestKey(entry.spendableEpoch(), entry.type(), entry.credType(), entry.credHash()),
                    entry.amount());
        }
        entries.putAll(writes);
        Map<String, BigInteger> perCredential = new LinkedHashMap<>();
        for (Map.Entry<RewardRestKey, BigInteger> entry : entries.entrySet()) {
            if (entry.getValue().signum() > 0) {
                perCredential.merge(entry.getKey().credential(), entry.getValue(), BigInteger::add);
            }
        }
        Map<String, BigInteger> credited = new LinkedHashMap<>();
        for (Map.Entry<String, BigInteger> entry : perCredential.entrySet()) {
            int separator = entry.getKey().indexOf(':');
            int credType = Integer.parseInt(entry.getKey().substring(0, separator));
            if (ledger.stakeAccount(credType, entry.getKey().substring(separator + 1)).isPresent()) {
                credited.put(entry.getKey(), entry.getValue());
            }
        }
        return credited;
    }

    // ------------------------------------------------------------------ builder

    private static final class Builder {
        private final int newEpoch;
        private String unavailableReason;
        private ProtocolParamsSnapshot protocolParams;
        private String protocolParamsUnavailableReason;
        private Set<String> retiredPools;
        private Map<String, BigInteger> poolDepositRefunds;
        private BigInteger unclaimedPoolDeposits;
        private String poolsUnavailableReason;
        private GovernanceEffects governance;
        private String governanceUnavailableReason;
        private Map<String, BigInteger> rewardRestCredits;

        Builder(int newEpoch) {
            this.newEpoch = newEpoch;
        }

        EpochBoundaryPreview build() {
            return new EpochBoundaryPreview(this);
        }
    }
}
