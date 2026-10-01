package org.yanoproject.ledger.rules.conway.gov;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
import org.yanoproject.ledger.rules.conway.ruleset.StateStep;
import org.yanoproject.ledger.rules.conway.tx.RawCredential;
import org.yanoproject.ledger.rules.conway.tx.RawParamUpdate;
import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.conway.tx.RawProposal.ProtVer;
import org.yanoproject.ledger.rules.conway.tx.RawVoter;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.GovPurpose;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Conway {@code GOV}'s units ({@code conwayGovTransition}, Conway/Rules/Gov.hs:446-613): the transaction-level check
 * ({@code ConwayScopes.GOV}), the checks and the state step of each proposal ({@code ConwayScopes.GOV_PROPOSAL},
 * {@code processProposal} :483-566) and the vote checks ({@code ConwayScopes.GOV_VOTES}, :568-608). Every check is its
 * own predicate (small-steps accumulates them; none short-circuits).
 */
public final class GovChecks {

    /**
     * {@code ppuWellFormed}'s "not 0 when present" parameter keys at every Conway protocol version
     * (Conway/PParams.hs:935-963): maxBBSize (2), maxTxSize (3), maxBHSize (4), maxValSize (22), collateralPercentage
     * (23), committeeMaxTermLength (28), govActionLifetime (29), poolDeposit (6), govActionDeposit (30), dRepDeposit (31).
     */
    public static final List<Integer> NON_ZERO_KEYS = List.of(2, 3, 4, 22, 23, 28, 29, 6, 30, 31);

    private GovChecks() {
    }

    // ------------------------------------------------------------------------------------------------ GOV

    /**
     * :478-481 ({@code hardforkConwayDisallowUnelectedCommitteeFromVoting}, before the proposals), 652-665: committee
     * voters whose hot credential no <em>elected</em> member (the committee before the transaction; it changes only at
     * a boundary) has authorised in the post-{@code CERTS} committee state
     * ({@code authorizedElectedHotCommitteeCredentials}, Governance.hs:581-591). Before protocol version 11 the
     * {@code MEMPOOL} rule checks it instead.
     */
    public static final class UnelectedCommitteeVoters extends PredicateCheck<GovSubject> {

        public UnelectedCommitteeVoters() {
            super(ConwayPredicate.UNELECTED_COMMITTEE_VOTERS);
        }

        @Override
        protected String detail(GovSubject g) {
            List<String> unelected = new ArrayList<>();
            for (RawVoter voter : g.ctx().raw().votes().keySet()) {
                if (voter.tag() > 1) {
                    continue;
                }
                CredentialKey hot = credential(voter);
                boolean elected = false;
                for (CommitteeMemberState member : g.afterCerts().committeeMembersByHot(hot).require("committee hot "
                        + "credential " + hot)) {
                    if (member.isElected() && !member.resigned()) {
                        elected = true;
                        break;
                    }
                }
                if (!elected) {
                    unelected.add((voter.isScript() ? "ScriptHashObj " : "KeyHashObj ") + hot.hashHex());
                }
            }
            return unelected.isEmpty() ? null : unelected.toString();
        }
    }

    // ------------------------------------------------------------------------------------------------ proposals

    /**
     * :483 {@code runTest $ checkBootstrapProposal} (:435-444, only while {@code hardforkConwayBootstrapPhase}): only
     * bootstrap actions ({@code ParameterChange}, {@code HardForkInitiation}, {@code InfoAction}, :633-639).
     */
    public static final class DisallowedProposalDuringBootstrap extends PredicateCheck<ProposalSubject> {

        public DisallowedProposalDuringBootstrap() {
            super(ConwayPredicate.DISALLOWED_PROPOSAL_DURING_BOOTSTRAP);
        }

        @Override
        protected String detail(ProposalSubject p) {
            RawProposal proposal = p.proposal();
            return GovRule.isBootstrapAction(proposal.actionTag()) ? null
                    : "ProposalProcedure " + proposal.index() + " (" + proposal.actionTypeName() + ")";
        }
    }

    /**
     * :488-499 {@code ProposalCantFollow}: {@code preceedingHardFork} (:673-695) with {@code pvCanFollow}: for a
     * hard-fork initiation whose parent is the enacted root, or whose major version is beyond the next one, the
     * previous version is the current protocol version; for a parent that is an in-flight hard-fork initiation, that
     * proposal's version; otherwise there is nothing to compare (the lineage check reports a bad parent).
     */
    public static final class ProposalCantFollow extends PredicateCheck<ProposalSubject> {

        public ProposalCantFollow() {
            super(ConwayPredicate.PROPOSAL_CANT_FOLLOW);
        }

        @Override
        protected String detail(ProposalSubject p) {
            RawProposal proposal = p.proposal();
            if (proposal.actionTag() != RawProposal.HARD_FORK_INITIATION) {
                return null;
            }
            TransitionContext ctx = p.ctx();
            Proposals proposals = p.gov().proposals();
            ProtVer supplied = proposal.protocolVersion();
            GovActionId parent = proposal.prevActionId();
            ProtVer previous;
            if (Objects.equals(parent, proposals.roots().root(GovPurpose.HARD_FORK))
                    || supplied.major() > ctx.protocolMajor() + 1L) {
                previous = currentProtocolVersion(ctx);
            } else if (parent != null) {
                Optional<GovAction> prior = proposals.lookup(parent);
                if (prior.isEmpty() || prior.get().actionTag() != RawProposal.HARD_FORK_INITIATION) {
                    return null;
                }
                previous = prior.get().hardForkVersion();
                if (previous == null) {
                    throw new LedgerStateUnavailableException("the protocol version of hard-fork proposal " + parent);
                }
            } else {
                return null;
            }
            if (supplied.canFollow(previous)) {
                return null;
            }
            return (parent != null ? "SJust (GovPurposeId " + parent + ")" : "SNothing")
                    + " Mismatch {mismatchSupplied = " + supplied + ", mismatchExpected = " + previous + "}";
        }

        private static ProtVer currentProtocolVersion(TransitionContext ctx) {
            ProtocolParams pp = ctx.params();
            Integer minor = pp.getProtocolMinorVer();
            if (minor == null) {
                throw new LedgerStateUnavailableException("the protocol minor version");
            }
            return new ProtVer(ctx.protocolMajor(), minor);
        }
    }

    /**
     * :502 {@code actionWellFormed} (:393-399): a parameter change must satisfy {@code ppuWellFormed pv}
     * (Conway/PParams.hs:935-963): not empty, and the listed parameters not 0 when present. The list depends on the
     * protocol version, so each version's rule set has its own instance ({@link #variant()}).
     */
    public static final class MalformedProposal extends PredicateCheck<ProposalSubject> {

        private final List<Integer> nonZeroKeys;

        /**
         * @param nonZeroKeys the parameter keys that must not be 0 when present, in {@code ppuWellFormed}'s order
         * @param haskellRef  where Haskell lists them
         */
        public MalformedProposal(List<Integer> nonZeroKeys, String haskellRef) {
            super(ConwayPredicate.MALFORMED_PROPOSAL, null, haskellRef);
            this.nonZeroKeys = List.copyOf(nonZeroKeys);
            if (new LinkedHashSet<>(nonZeroKeys).size() != nonZeroKeys.size()) {
                throw new IllegalArgumentException("duplicate keys " + nonZeroKeys);
            }
        }

        /** @return this check with {@code keys} added to the list */
        public MalformedProposal withNonZero(String haskellRef, Integer... keys) {
            SequencedSet<Integer> all = new LinkedHashSet<>(nonZeroKeys);
            Collections.addAll(all, keys);
            return new MalformedProposal(List.copyOf(all), haskellRef);
        }

        /** @return the parameter keys that must not be 0 when present */
        public List<Integer> nonZeroKeys() {
            return nonZeroKeys;
        }

        @Override
        public String variant() {
            return "nonZero=" + nonZeroKeys;
        }

        @Override
        protected String detail(ProposalSubject p) {
            RawParamUpdate update = p.proposal().paramUpdate();
            if (update == null) {
                return null;
            }
            SortedSet<Integer> bad = update.malformedKeys(nonZeroKeys);
            return bad.isEmpty() ? null : "ParameterChange " + update + " (not well formed: " + bad + ")";
        }
    }

    /**
     * :504-508 ({@code unless hardforkConwayBootstrapPhase}, so from protocol version 10): the return account must be
     * registered in the post-{@code CERTS} state (the credential only).
     */
    public static final class ProposalReturnAccountDoesNotExist extends PredicateCheck<ProposalSubject> {

        public ProposalReturnAccountDoesNotExist() {
            super(ConwayPredicate.PROPOSAL_RETURN_ACCOUNT_DOES_NOT_EXIST);
        }

        @Override
        protected String detail(ProposalSubject p) {
            byte[] account = p.proposal().returnAccount();
            return registered(p.gov().afterCerts(), account) ? null : account(account);
        }
    }

    /**
     * :509-520 ({@code unless hardforkConwayBootstrapPhase}, so from protocol version 10): a treasury withdrawal's
     * accounts must be registered in the post-{@code CERTS} state.
     */
    public static final class TreasuryWithdrawalReturnAccountsDoNotExist extends PredicateCheck<ProposalSubject> {

        public TreasuryWithdrawalReturnAccountsDoNotExist() {
            super(ConwayPredicate.TREASURY_WITHDRAWAL_RETURN_ACCOUNTS_DO_NOT_EXIST);
        }

        @Override
        protected String detail(ProposalSubject p) {
            if (p.proposal().actionTag() != RawProposal.TREASURY_WITHDRAWALS) {
                return null;
            }
            List<String> missing = new ArrayList<>();
            for (RawProposal.Withdrawal w : p.proposal().withdrawals()) {
                if (!registered(p.gov().afterCerts(), w.account())) {
                    missing.add(account(w.account()));
                }
            }
            return missing.isEmpty() ? null : missing.toString();
        }
    }

    /** :522-530: {@code pProcDeposit == ppGovActionDeposit}. */
    public static final class ProposalDepositIncorrect extends PredicateCheck<ProposalSubject> {

        public ProposalDepositIncorrect() {
            super(ConwayPredicate.PROPOSAL_DEPOSIT_INCORRECT);
        }

        @Override
        protected String detail(ProposalSubject p) {
            BigInteger expected = new ConwayParams(p.ctx().params()).govActionDeposit();
            BigInteger deposit = p.proposal().deposit();
            return deposit.equals(expected) ? null
                    : "Mismatch {mismatchSupplied = Coin " + deposit + ", mismatchExpected = Coin " + expected + "}";
        }
    }

    /** :532-535: the return account's network. */
    public static final class ProposalProcedureNetworkIdMismatch extends PredicateCheck<ProposalSubject> {

        public ProposalProcedureNetworkIdMismatch() {
            super(ConwayPredicate.PROPOSAL_PROCEDURE_NETWORK_ID_MISMATCH);
        }

        @Override
        protected String detail(ProposalSubject p) {
            return p.proposal().returnAccountNetwork() == p.network() ? null
                    : account(p.proposal().returnAccount()) + " " + networkName(p.network());
        }
    }

    /** :539-544 (treasury withdrawals): the withdrawal accounts' network. */
    public static final class TreasuryWithdrawalsNetworkIdMismatch extends PredicateCheck<ProposalSubject> {

        public TreasuryWithdrawalsNetworkIdMismatch() {
            super(ConwayPredicate.TREASURY_WITHDRAWALS_NETWORK_ID_MISMATCH);
        }

        @Override
        protected String detail(ProposalSubject p) {
            if (p.proposal().actionTag() != RawProposal.TREASURY_WITHDRAWALS) {
                return null;
            }
            List<String> mismatched = new ArrayList<>();
            for (RawProposal.Withdrawal w : p.proposal().withdrawals()) {
                if (w.network() != p.network()) {
                    mismatched.add(account(w.account()));
                }
            }
            return mismatched.isEmpty() ? null : mismatched + " " + networkName(p.network());
        }
    }

    /**
     * {@code runTest checkGuardrailsScriptHash} (:420-426): the proposal's policy must be the constitution's
     * guardrail. Haskell checks it for treasury withdrawals (:547) and, later, for parameter changes (:557-558).
     */
    public static final class InvalidGuardrailsScriptHash extends PredicateCheck<ProposalSubject> {

        private final int actionTag;

        /** :547, after {@code TreasuryWithdrawalsNetworkIdMismatch}. */
        public static InvalidGuardrailsScriptHash forTreasuryWithdrawals() {
            return new InvalidGuardrailsScriptHash(RawProposal.TREASURY_WITHDRAWALS, "treasuryWithdrawals",
                    "Conway/Rules/Gov.hs:420-426, 547 (TreasuryWithdrawals): runTest checkGuardrailsScriptHash");
        }

        /** :557-558, the parameter change's only action-specific check. */
        public static InvalidGuardrailsScriptHash forParameterChange() {
            return new InvalidGuardrailsScriptHash(RawProposal.PARAMETER_CHANGE, "parameterChange",
                    "Conway/Rules/Gov.hs:420-426, 557-558 (ParameterChange): runTest checkGuardrailsScriptHash");
        }

        private InvalidGuardrailsScriptHash(int actionTag, String suffix, String haskellRef) {
            super(ConwayPredicate.INVALID_GUARDRAILS_SCRIPT_HASH, suffix, haskellRef);
            this.actionTag = actionTag;
        }

        @Override
        protected String detail(ProposalSubject p) {
            if (p.proposal().actionTag() != actionTag) {
                return null;
            }
            String expected = p.ctx().preState().guardrailScriptHash().orElseThrowUnavailable().orElse(null);
            byte[] policy = p.proposal().policyHash();
            String actual = policy != null ? HexUtil.encodeHexString(policy) : null;
            if (Objects.equals(actual, expected)) {
                return null;
            }
            return strictMaybe(actual) + " " + strictMaybe(expected);
        }
    }

    /** :550 ({@code F.fold wdrls /= mempty}, so also an empty map): a treasury withdrawal of nothing. */
    public static final class ZeroTreasuryWithdrawals extends PredicateCheck<ProposalSubject> {

        public ZeroTreasuryWithdrawals() {
            super(ConwayPredicate.ZERO_TREASURY_WITHDRAWALS);
        }

        @Override
        protected String detail(ProposalSubject p) {
            if (p.proposal().actionTag() != RawProposal.TREASURY_WITHDRAWALS) {
                return null;
            }
            BigInteger total = p.proposal().withdrawals().stream().map(RawProposal.Withdrawal::amount)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            return total.signum() != 0 ? null : "TreasuryWithdrawals " + p.proposal().withdrawals();
        }
    }

    /** :551-553 (update committee): a member both added and removed. */
    public static final class ConflictingCommitteeUpdate extends PredicateCheck<ProposalSubject> {

        public ConflictingCommitteeUpdate() {
            super(ConwayPredicate.CONFLICTING_COMMITTEE_UPDATE);
        }

        @Override
        protected String detail(ProposalSubject p) {
            if (p.proposal().actionTag() != RawProposal.UPDATE_COMMITTEE) {
                return null;
            }
            List<RawCredential> conflicting = new ArrayList<>();
            for (RawCredential added : p.proposal().committeeAdditions().keySet()) {
                if (p.proposal().committeeRemovals().contains(added)) {
                    conflicting.add(added);
                }
            }
            return conflicting.isEmpty() ? null : conflicting.toString();
        }
    }

    /** :555-556 (update committee): an added member's expiry at most the current epoch. */
    public static final class ExpirationEpochTooSmall extends PredicateCheck<ProposalSubject> {

        public ExpirationEpochTooSmall() {
            super(ConwayPredicate.EXPIRATION_EPOCH_TOO_SMALL);
        }

        @Override
        protected String detail(ProposalSubject p) {
            if (p.proposal().actionTag() != RawProposal.UPDATE_COMMITTEE) {
                return null;
            }
            BigInteger current = BigInteger.valueOf(p.ctx().env().currentEpoch());
            Map<RawCredential, BigInteger> tooSmall = new TreeMap<>();
            p.proposal().committeeAdditions().forEach((member, epoch) -> {
                if (epoch.compareTo(current) <= 0) {
                    tooSmall.put(member, epoch);
                }
            });
            return tooSmall.isEmpty() ? null : tooSmall.toString();
        }
    }

    /**
     * :561-566 {@code proposalsAddAction} (Governance/Proposals.hs:297-333): the proposal joins {@code Proposals} when
     * its parent is the purpose's enacted root or a proposal of the same purpose; the state changes whether or not the
     * predicate is reported, and {@code InvalidPrevGovActionId} reads the result.
     */
    public static final class ProposalsAddAction extends StateStep<ProposalSubject> {

        public ProposalsAddAction() {
            super("GOV.proposalsAddAction", "Conway/Rules/Gov.hs:561-566 (proposalsAddAction, "
                    + "Governance/Proposals.hs:297-333); expiry currentEpoch + govActionLifetime (:483-486)");
        }

        @Override
        protected void advance(ProposalSubject p) {
            RawProposal proposal = p.proposal();
            TransitionContext ctx = p.ctx();
            GovAction action = new GovAction(p.id(), proposal.actionTag(),
                    ctx.env().currentEpoch() + requireInt(ctx.params().getGovActionLifetime(), "govActionLifetime"),
                    proposal.protocolVersion(),
                    proposal.paramUpdate() != null ? proposal.paramUpdate().anyInSecurityGroup() : Boolean.FALSE);
            p.added(p.gov().proposals().add(action, proposal.prevActionId()));
        }
    }

    /** :561-566 {@code failBecause InvalidPrevGovActionId}: the parent is not the root nor a same-purpose proposal. */
    public static final class InvalidPrevGovActionId extends PredicateCheck<ProposalSubject> {

        public InvalidPrevGovActionId() {
            super(ConwayPredicate.INVALID_PREV_GOV_ACTION_ID);
        }

        @Override
        protected String detail(ProposalSubject p) {
            RawProposal proposal = p.proposal();
            return p.added() ? null : "ProposalProcedure " + proposal.index() + " (" + proposal.actionTypeName()
                    + ", parent " + (proposal.prevActionId() != null ? proposal.prevActionId() : "SNothing") + ")";
        }
    }

    // ------------------------------------------------------------------------------------------------ votes

    /** :604 {@code failOnNonEmpty unknownVoters VotersDoNotExist}. */
    public static final class VotersDoNotExist extends PredicateCheck<VotesSubject> {

        public VotersDoNotExist() {
            super(ConwayPredicate.VOTERS_DO_NOT_EXIST);
        }

        @Override
        protected String detail(VotesSubject v) {
            return v.unknownVoters().isEmpty() ? null : v.unknownVoters().toString();
        }
    }

    /** :605 {@code failOnNonEmpty unknownGovActionIds GovActionsDoNotExist} (known voters only). */
    public static final class GovActionsDoNotExist extends PredicateCheck<VotesSubject> {

        public GovActionsDoNotExist() {
            super(ConwayPredicate.GOV_ACTIONS_DO_NOT_EXIST);
        }

        @Override
        protected String detail(VotesSubject v) {
            return v.unknownActions().isEmpty() ? null : v.unknownActions().toString();
        }
    }

    /**
     * :606 {@code checkBootstrapVotes} (:378-391, only while {@code hardforkConwayBootstrapPhase}): a DRep votes only on
     * an {@code InfoAction}; the committee and stake pools only on a bootstrap action.
     */
    public static final class DisallowedVotesDuringBootstrap extends PredicateCheck<VotesSubject> {

        public DisallowedVotesDuringBootstrap() {
            super(ConwayPredicate.DISALLOWED_VOTES_DURING_BOOTSTRAP);
        }

        @Override
        protected String detail(VotesSubject v) {
            return disallowed(v.known(), vote -> {
                int actionTag = vote.getValue().actionTag();
                return switch (vote.getKey().tag()) {
                    case 2, 3 -> actionTag == RawProposal.INFO;
                    default -> GovRule.isBootstrapAction(actionTag);
                };
            });
        }
    }

    /** :607 {@code checkVotesAreNotForExpiredActions}: {@code currentEpoch > gasExpiresAfter}. */
    public static final class VotingOnExpiredGovAction extends PredicateCheck<VotesSubject> {

        public VotingOnExpiredGovAction() {
            super(ConwayPredicate.VOTING_ON_EXPIRED_GOV_ACTION);
        }

        @Override
        protected String detail(VotesSubject v) {
            long epoch = v.gov().ctx().env().currentEpoch();
            return disallowed(v.known(), vote -> epoch <= vote.getValue().expiresAfter());
        }
    }

    /**
     * :608 {@code checkVotersAreValid} (:364-376, Governance/Internal.hs:350-497): committee not on
     * {@code NoConfidence} or {@code UpdateCommittee}; DReps always allowed; stake pools on {@code NoConfidence},
     * {@code UpdateCommittee}, {@code HardForkInitiation}, {@code InfoAction} and parameter changes that touch the
     * security group.
     */
    public static final class DisallowedVoters extends PredicateCheck<VotesSubject> {

        public DisallowedVoters() {
            super(ConwayPredicate.DISALLOWED_VOTERS);
        }

        @Override
        protected String detail(VotesSubject v) {
            return disallowed(v.known(), DisallowedVoters::votingAllowed);
        }

        private static boolean votingAllowed(Map.Entry<RawVoter, GovAction> vote) {
            GovAction action = vote.getValue();
            return switch (vote.getKey().tag()) {
                // isCommitteeVotingAllowed: NoVotingAllowed for NoConfidence and UpdateCommittee only.
                case 0, 1 -> action.actionTag() != RawProposal.NO_CONFIDENCE
                        && action.actionTag() != RawProposal.UPDATE_COMMITTEE;
                // isDRepVotingAllowed: every action has a DRep threshold (or none, InfoAction).
                case 2, 3 -> true;
                // isStakePoolVotingAllowed: not NewConstitution, TreasuryWithdrawals, or a parameter change outside
                // the security group.
                default -> switch (action.actionTag()) {
                    case RawProposal.NEW_CONSTITUTION, RawProposal.TREASURY_WITHDRAWALS -> false;
                    case RawProposal.PARAMETER_CHANGE -> {
                        if (action.securityGroup() == null) {
                            throw new LedgerStateUnavailableException("the parameter-update keys of proposal "
                                    + action.id());
                        }
                        yield action.securityGroup();
                    }
                    default -> true;
                };
            };
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String disallowed(List<Map.Entry<RawVoter, GovAction>> votes,
                                     Predicate<Map.Entry<RawVoter, GovAction>> allowed) {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<RawVoter, GovAction> vote : votes) {
            if (!allowed.test(vote)) {
                bad.add("(" + vote.getKey() + ", " + vote.getValue().id() + ")");
            }
        }
        return bad.isEmpty() ? null : bad.toString();
    }

    static CredentialKey credential(RawVoter voter) {
        return new CredentialKey(voter.isScript() ? CredentialType.SCRIPT : CredentialType.KEY,
                HexUtil.encodeHexString(voter.hash()));
    }

    /** {@code isAccountRegistered (addr ^. accountAddressCredentialL)}: the credential only, any network. */
    private static boolean registered(LedgerView state, byte[] accountAddress) {
        byte[] hash = new byte[28];
        System.arraycopy(accountAddress, 1, hash, 0, 28);
        CredentialKey credential = new CredentialKey((accountAddress[0] & 0x10) != 0 ? CredentialType.SCRIPT
                : CredentialType.KEY, HexUtil.encodeHexString(hash));
        return state.account(credential).orElseThrowUnavailable().isPresent();
    }

    private static String account(byte[] accountAddress) {
        return "AccountAddress " + HexUtil.encodeHexString(accountAddress);
    }

    private static String networkName(int network) {
        return network == 1 ? "Mainnet" : "Testnet";
    }

    private static String strictMaybe(String scriptHash) {
        return scriptHash == null ? "SNothing" : "SJust (ScriptHash \"" + scriptHash + "\")";
    }

    private static long requireInt(Integer value, String name) {
        if (value == null) {
            throw new LedgerStateUnavailableException("protocol parameter " + name);
        }
        return value;
    }
}
