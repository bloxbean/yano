package org.yanoproject.ledger.rules.conway.gov;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.certs.CertState;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.RawCredential;
import org.yanoproject.ledger.rules.conway.tx.RawParamUpdate;
import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.conway.tx.RawProposal.ProtVer;
import org.yanoproject.ledger.rules.conway.tx.RawVoter;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.GovPurpose;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Conway {@code GOV} ({@code conwayGovTransition}, Conway/Rules/Gov.hs:446-613), run by {@code LEDGER} when
 * {@code isValid = True}, after {@code CERTS} (Ledger.hs:402-421). Every check is its own predicate (small-steps
 * accumulates them; none short-circuits), in Haskell's order:
 *
 * <ol>
 *   <li>{@code UnelectedCommitteeVoters} (PV ≥ 11, :478-481): committee voters whose hot credential no <em>elected</em>
 *       member (the committee before the transaction; it changes only at a boundary) has authorised in the post-CERTS
 *       committee state ({@code authorizedElectedHotCommitteeCredentials}, Governance.hs:581-591). Before 11 the
 *       {@code MEMPOOL} rule checks it ({@code MempoolRule}).</li>
 *   <li>Each proposal in body order ({@code processProposal}, :483-566): at protocol version 9 only
 *       ({@code hardforkConwayBootstrapPhase}) {@code DisallowedProposalDuringBootstrap} (not a
 *       {@code ParameterChange}, {@code HardForkInitiation} or {@code InfoAction}, :435-444);
 *       {@code ProposalCantFollow} (hard forks: the version must follow the enacted root's — the current protocol
 *       version — or the in-flight parent's, {@code preceedingHardFork} :673-695); {@code MalformedProposal}
 *       ({@code ppuWellFormed}, {@link RawParamUpdate#malformedKeys(int)}); from protocol version 10
 *       {@code ProposalReturnAccountDoesNotExist} and, for treasury withdrawals,
 *       {@code TreasuryWithdrawalReturnAccountsDoNotExist} (post-CERTS accounts, the credential only);
 *       {@code ProposalDepositIncorrect}; {@code ProposalProcedureNetworkIdMismatch}; then per action:
 *       treasury withdrawals {@code TreasuryWithdrawalsNetworkIdMismatch}, {@code InvalidGuardrailsScriptHash},
 *       {@code ZeroTreasuryWithdrawals}; update committee {@code ConflictingCommitteeUpdate},
 *       {@code ExpirationEpochTooSmall}; parameter change {@code InvalidGuardrailsScriptHash}; last the lineage
 *       ({@code proposalsAddAction}, Governance/Proposals.hs:297-333): the parent must be the purpose's enacted root or
 *       a proposal of the same purpose in {@code Proposals} — the state before the transaction plus the proposals of
 *       this transaction accepted so far — otherwise {@code InvalidPrevGovActionId} and the proposal is not added.</li>
 *   <li>The votes, against the proposals after step 2 (so a vote on an earlier proposal of the same transaction is a
 *       vote on an existing action): {@code VotersDoNotExist} (post-CERTS state: committee hot credentials with a
 *       current authorisation, registered DReps, registered pools), {@code GovActionsDoNotExist} (only the votes of
 *       known voters), at protocol version 9 only {@code DisallowedVotesDuringBootstrap} (DReps only on
 *       {@code InfoAction}, committee and stake pools only on bootstrap actions, :378-391),
 *       {@code VotingOnExpiredGovAction} ({@code currentEpoch > gasExpiresAfter}) and
 *       {@code DisallowedVoters} (committee: not on {@code NoConfidence} or {@code UpdateCommittee}; DReps: always
 *       allowed; stake pools: {@code NoConfidence}, {@code UpdateCommittee}, {@code HardForkInitiation},
 *       {@code InfoAction} and parameter changes that touch the security group,
 *       Governance/Internal.hs:350-497).</li>
 * </ol>
 *
 * <p>Protocol-version differences are the checks' {@link ConwayPredicate#pvRange()} ranges, applied by
 * {@link TransitionContext#check}; the rule itself has no version branches.</p>
 *
 * <p>{@code GOV}'s own state change (proposals and votes into {@code Proposals}) is the effects deriver's; this rule
 * only validates. Unavailable reads fail closed ({@link LedgerStateUnavailableException}).</p>
 */
public final class GovRule {

    private GovRule() {
    }

    /** One governance action in {@code Proposals}, as far as {@code GOV} reads it. */
    private record Action(GovActionId id, int actionTag, long expiresAfter, ProtVer hardForkVersion,
                          Boolean securityGroup) {

        GovPurpose purpose() {
            return purposeOf(actionTag);
        }
    }

    /** Runs {@code GOV} and folds it into {@code ledger}. */
    public static void apply(RuleFrame ledger) {
        apply(ledger, ledger.context().raw().txIdHex());
    }

    /**
     * Runs {@code GOV} with this transaction's proposals identified as {@code (proposalTxId, index)}.
     *
     * <p>Haskell lets a proposal name an earlier proposal of the same transaction as its parent, and a vote name a
     * proposal of the same transaction ({@code proposalsAddAction} and {@code proposalsActionsMap} see the proposals
     * added so far). A real transaction cannot do either: the action id contains the transaction id, the hash of the
     * body that would have to name it. Tests pass another id to exercise the semantics; production uses
     * {@link #apply(RuleFrame)}.</p>
     */
    public static void apply(RuleFrame ledger, String proposalTxId) {
        TransitionContext ctx = ledger.context();
        RuleFrame gov = ledger.child(LedgerRuleName.GOV);
        LedgerView afterCerts = ctx.certState().current();

        // :478-481 when hardforkConwayDisallowUnelectedCommitteeFromVoting $ failOnNonEmpty (unelectedCommitteeVoters …)
        ctx.check(gov, ConwayPredicate.UNELECTED_COMMITTEE_VOTERS, () -> unelectedCommitteeVoters(ctx, afterCerts));

        Proposals proposals = new Proposals(ctx.preState());
        for (RawProposal proposal : ctx.raw().proposals()) {
            processProposal(gov, proposal, new GovActionId(proposalTxId, proposal.index()), proposals, afterCerts);
        }
        votes(gov, proposals, afterCerts);
        ledger.subRule(gov);
    }

    // ------------------------------------------------------------------------------------------------ proposals

    /** {@code processProposal} (:483-566). */
    private static void processProposal(RuleFrame gov, RawProposal proposal, GovActionId newId, Proposals proposals,
                                        LedgerView afterCerts) {
        TransitionContext ctx = gov.context();
        int network = CertState.network(ctx);

        // :483 runTest $ checkBootstrapProposal (only while hardforkConwayBootstrapPhase: PvRange.BOOTSTRAP)
        ctx.check(gov, ConwayPredicate.DISALLOWED_PROPOSAL_DURING_BOOTSTRAP, () -> isBootstrapAction(
                proposal.actionTag()) ? null : "ProposalProcedure " + proposal.index() + " ("
                + proposal.actionTypeName() + ")");
        // :488-499 ProposalCantFollow
        ctx.check(gov, ConwayPredicate.PROPOSAL_CANT_FOLLOW, () -> badHardFork(ctx, proposal, proposals));
        // :502 actionWellFormed
        ctx.check(gov, ConwayPredicate.MALFORMED_PROPOSAL, () -> {
            RawParamUpdate update = proposal.paramUpdate();
            if (update == null) {
                return null;
            }
            SortedSet<Integer> bad = update.malformedKeys(ctx.protocolMajor());
            return bad.isEmpty() ? null : "ParameterChange " + update + " (not well formed: " + bad + ")";
        });
        // :504-520 unless hardforkConwayBootstrapPhase (PvRange.POST_BOOTSTRAP): return account and treasury
        // withdrawal accounts registered (post-CERTS)
        ctx.check(gov, ConwayPredicate.PROPOSAL_RETURN_ACCOUNT_DOES_NOT_EXIST,
                () -> registered(afterCerts, proposal.returnAccount()) ? null : account(proposal.returnAccount()));
        if (proposal.actionTag() == RawProposal.TREASURY_WITHDRAWALS) {
            ctx.check(gov, ConwayPredicate.TREASURY_WITHDRAWAL_RETURN_ACCOUNTS_DO_NOT_EXIST, () -> {
                List<String> missing = new ArrayList<>();
                for (RawProposal.Withdrawal w : proposal.withdrawals()) {
                    if (!registered(afterCerts, w.account())) {
                        missing.add(account(w.account()));
                    }
                }
                return missing.isEmpty() ? null : missing.toString();
            });
        }
        // :522-530 deposit
        ctx.check(gov, ConwayPredicate.PROPOSAL_DEPOSIT_INCORRECT, () -> {
            BigInteger expected = new ConwayParams(ctx.params()).govActionDeposit();
            return proposal.deposit().equals(expected) ? null
                    : "Mismatch {mismatchSupplied = Coin " + proposal.deposit() + ", mismatchExpected = Coin "
                    + expected + "}";
        });
        // :532-535 return account network
        ctx.check(gov, ConwayPredicate.PROPOSAL_PROCEDURE_NETWORK_ID_MISMATCH,
                () -> proposal.returnAccountNetwork() == network ? null
                        : account(proposal.returnAccount()) + " " + networkName(network));
        // :538-559 per action
        switch (proposal.actionTag()) {
            case RawProposal.TREASURY_WITHDRAWALS -> {
                ctx.check(gov, ConwayPredicate.TREASURY_WITHDRAWALS_NETWORK_ID_MISMATCH, () -> {
                    List<String> mismatched = new ArrayList<>();
                    for (RawProposal.Withdrawal w : proposal.withdrawals()) {
                        if (w.network() != network) {
                            mismatched.add(account(w.account()));
                        }
                    }
                    return mismatched.isEmpty() ? null : mismatched + " " + networkName(network);
                });
                ctx.check(gov, ConwayPredicate.INVALID_GUARDRAILS_SCRIPT_HASH, () -> guardrails(ctx, proposal));
                ctx.check(gov, ConwayPredicate.ZERO_TREASURY_WITHDRAWALS, () -> {
                    BigInteger total = proposal.withdrawals().stream().map(RawProposal.Withdrawal::amount)
                            .reduce(BigInteger.ZERO, BigInteger::add);
                    return total.signum() != 0 ? null : "TreasuryWithdrawals " + proposal.withdrawals();
                });
            }
            case RawProposal.UPDATE_COMMITTEE -> {
                ctx.check(gov, ConwayPredicate.CONFLICTING_COMMITTEE_UPDATE, () -> {
                    List<RawCredential> conflicting = new ArrayList<>();
                    for (RawCredential added : proposal.committeeAdditions().keySet()) {
                        if (proposal.committeeRemovals().contains(added)) {
                            conflicting.add(added);
                        }
                    }
                    return conflicting.isEmpty() ? null : conflicting.toString();
                });
                ctx.check(gov, ConwayPredicate.EXPIRATION_EPOCH_TOO_SMALL, () -> {
                    BigInteger current = BigInteger.valueOf(ctx.env().currentEpoch());
                    Map<RawCredential, BigInteger> tooSmall = new TreeMap<>();
                    proposal.committeeAdditions().forEach((member, epoch) -> {
                        if (epoch.compareTo(current) <= 0) {
                            tooSmall.put(member, epoch);
                        }
                    });
                    return tooSmall.isEmpty() ? null : tooSmall.toString();
                });
            }
            case RawProposal.PARAMETER_CHANGE ->
                    ctx.check(gov, ConwayPredicate.INVALID_GUARDRAILS_SCRIPT_HASH, () -> guardrails(ctx, proposal));
            default -> {
                // no action-specific checks
            }
        }
        // :561-566 ancestry: proposalsAddAction
        Action action = new Action(newId, proposal.actionTag(),
                ctx.env().currentEpoch() + requireInt(ctx.params().getGovActionLifetime(), "govActionLifetime"),
                proposal.protocolVersion(),
                proposal.paramUpdate() != null ? proposal.paramUpdate().anyInSecurityGroup() : Boolean.FALSE);
        // proposalsAddAction changes Proposals whether or not the predicate is reported; only its result is checked.
        boolean added = proposals.add(action, proposal.prevActionId());
        ctx.check(gov, ConwayPredicate.INVALID_PREV_GOV_ACTION_ID, () -> added ? null
                : "ProposalProcedure " + proposal.index() + " (" + proposal.actionTypeName() + ", parent "
                + (proposal.prevActionId() != null ? proposal.prevActionId() : "SNothing") + ")");
    }

    /**
     * {@code preceedingHardFork} (:673-695) with {@code pvCanFollow}: for a hard-fork initiation whose parent is the
     * enacted root, or whose major version is beyond the next one, the previous version is the current protocol
     * version; for a parent that is an in-flight hard-fork initiation, that proposal's version; otherwise there is
     * nothing to compare (the lineage check reports a bad parent).
     */
    private static String badHardFork(TransitionContext ctx, RawProposal proposal, Proposals proposals) {
        if (proposal.actionTag() != RawProposal.HARD_FORK_INITIATION) {
            return null;
        }
        ProtVer supplied = proposal.protocolVersion();
        GovActionId parent = proposal.prevActionId();
        ProtVer previous;
        if (Objects.equals(parent, proposals.roots().root(GovPurpose.HARD_FORK))
                || supplied.major() > ctx.protocolMajor() + 1L) {
            previous = currentProtocolVersion(ctx);
        } else if (parent != null) {
            Optional<Action> prior = proposals.lookup(parent);
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

    /** {@code checkGuardrailsScriptHash} (:420-426): the proposal's policy must be the constitution's guardrail. */
    private static String guardrails(TransitionContext ctx, RawProposal proposal) {
        String expected = ctx.preState().guardrailScriptHash().orElseThrowUnavailable().orElse(null);
        byte[] policy = proposal.policyHash();
        String actual = policy != null ? HexUtil.encodeHexString(policy) : null;
        if (Objects.equals(actual, expected)) {
            return null;
        }
        return strictMaybe(actual) + " " + strictMaybe(expected);
    }

    // ------------------------------------------------------------------------------------------------ votes

    /** The votes (:568-608). */
    private static void votes(RuleFrame gov, Proposals proposals, LedgerView afterCerts) {
        TransitionContext ctx = gov.context();
        Map<RawVoter, List<GovActionId>> votes = ctx.raw().votes();
        if (votes.isEmpty()) {
            return;
        }
        List<RawVoter> unknownVoters = new ArrayList<>();
        List<GovActionId> unknownActions = new ArrayList<>();
        List<Map.Entry<RawVoter, Action>> known = new ArrayList<>();
        votes.forEach((voter, ids) -> {
            if (!voterExists(afterCerts, voter)) {
                unknownVoters.add(voter);
                return;
            }
            for (GovActionId id : ids) {
                Optional<Action> action = proposals.lookup(id);
                if (action.isPresent()) {
                    known.add(Map.entry(voter, action.get()));
                } else {
                    unknownActions.add(id);
                }
            }
        });
        // :604 failOnNonEmpty unknownVoters VotersDoNotExist
        ctx.check(gov, ConwayPredicate.VOTERS_DO_NOT_EXIST,
                () -> unknownVoters.isEmpty() ? null : unknownVoters.toString());
        // :605 failOnNonEmpty unknownGovActionIds GovActionsDoNotExist
        ctx.check(gov, ConwayPredicate.GOV_ACTIONS_DO_NOT_EXIST,
                () -> unknownActions.isEmpty() ? null : unknownActions.toString());
        // :606 checkBootstrapVotes (only while hardforkConwayBootstrapPhase: PvRange.BOOTSTRAP)
        ctx.check(gov, ConwayPredicate.DISALLOWED_VOTES_DURING_BOOTSTRAP,
                () -> disallowed(known, GovRule::bootstrapVoteAllowed));
        // :607 checkVotesAreNotForExpiredActions
        long epoch = ctx.env().currentEpoch();
        ctx.check(gov, ConwayPredicate.VOTING_ON_EXPIRED_GOV_ACTION, () -> disallowed(known,
                a -> epoch <= a.getValue().expiresAfter()));
        // :608 checkVotersAreValid
        ctx.check(gov, ConwayPredicate.DISALLOWED_VOTERS, () -> disallowed(known, GovRule::votingAllowed));
    }

    private static String disallowed(List<Map.Entry<RawVoter, Action>> votes,
                                     Predicate<Map.Entry<RawVoter, Action>> allowed) {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<RawVoter, Action> vote : votes) {
            if (!allowed.test(vote)) {
                bad.add("(" + vote.getKey() + ", " + vote.getValue().id() + ")");
            }
        }
        return bad.isEmpty() ? null : bad.toString();
    }

    /**
     * {@code checkBootstrapVotes} (:378-391), the bootstrap-phase voter rule: a DRep votes only on an
     * {@code InfoAction}; the committee and stake pools only on a bootstrap action ({@link #isBootstrapAction}).
     */
    private static boolean bootstrapVoteAllowed(Map.Entry<RawVoter, Action> vote) {
        int actionTag = vote.getValue().actionTag();
        return switch (vote.getKey().tag()) {
            case 2, 3 -> actionTag == RawProposal.INFO;
            default -> isBootstrapAction(actionTag);
        };
    }

    /**
     * {@code isBootstrapAction} (:633-639): the actions allowed during the bootstrap phase ({@code ParameterChange},
     * {@code HardForkInitiation}, {@code InfoAction}).
     */
    static boolean isBootstrapAction(int actionTag) {
        return actionTag == RawProposal.PARAMETER_CHANGE || actionTag == RawProposal.HARD_FORK_INITIATION
                || actionTag == RawProposal.INFO;
    }

    /** {@code checkVotersAreValid} (:364-376, Governance/Internal.hs:350-497). */
    private static boolean votingAllowed(Map.Entry<RawVoter, Action> vote) {
        Action action = vote.getValue();
        return switch (vote.getKey().tag()) {
            // isCommitteeVotingAllowed: NoVotingAllowed for NoConfidence and UpdateCommittee only.
            case 0, 1 -> action.actionTag() != RawProposal.NO_CONFIDENCE
                    && action.actionTag() != RawProposal.UPDATE_COMMITTEE;
            // isDRepVotingAllowed: every action has a DRep threshold (or none, InfoAction).
            case 2, 3 -> true;
            // isStakePoolVotingAllowed: not NewConstitution, TreasuryWithdrawals, or a parameter change outside the
            // security group.
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

    /**
     * {@code internVoter} (:586-590) against the post-CERTS state: a committee hot credential with a current
     * (non-resigned) authorisation ({@code authorizedHotCommitteeCredentials}), a registered DRep, a registered pool.
     */
    private static boolean voterExists(LedgerView state, RawVoter voter) {
        CredentialKey credential = new CredentialKey(voter.isScript() ? CredentialType.SCRIPT : CredentialType.KEY,
                HexUtil.encodeHexString(voter.hash()));
        return switch (voter.tag()) {
            case 0, 1 -> !state.committeeMembersByHot(credential).require("committee hot credential " + credential)
                    .isEmpty();
            case 2, 3 -> state.drep(credential).orElseThrowUnavailable().isPresent();
            default -> state.pool(new PoolId(credential.hashHex())).orElseThrowUnavailable().isPresent();
        };
    }

    /**
     * {@code unelectedCommitteeVoters} (:652-665): the committee voters whose hot credential is not authorised by an
     * elected member in the post-CERTS committee state.
     */
    private static String unelectedCommitteeVoters(TransitionContext ctx, LedgerView afterCerts) {
        List<String> unelected = new ArrayList<>();
        for (RawVoter voter : ctx.raw().votes().keySet()) {
            if (voter.tag() > 1) {
                continue;
            }
            CredentialKey hot = new CredentialKey(voter.isScript() ? CredentialType.SCRIPT : CredentialType.KEY,
                    HexUtil.encodeHexString(voter.hash()));
            boolean elected = false;
            for (CommitteeMemberState member : afterCerts.committeeMembersByHot(hot).require("committee hot "
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

    // ------------------------------------------------------------------------------------------------ Proposals

    /**
     * Haskell's {@code Proposals} as {@code GOV} folds it: the proposals before the transaction (the view) plus the
     * proposals of this transaction accepted so far, with the enacted roots.
     */
    private static final class Proposals {

        private final LedgerView pre;
        private final Map<GovActionId, Action> added = new HashMap<>();
        private EnactedRoots roots;

        Proposals(LedgerView pre) {
            this.pre = pre;
        }

        EnactedRoots roots() {
            if (roots == null) {
                roots = pre.enactedRoots().require("enacted roots");
            }
            return roots;
        }

        /** {@code proposalsLookupId}. */
        Optional<Action> lookup(GovActionId id) {
            Action mine = added.get(id);
            if (mine != null) {
                return Optional.of(mine);
            }
            return switch (pre.proposal(id)) {
                case Lookup.Present<ProposalState> p -> Optional.of(of(p.value()));
                case Lookup.Absent<ProposalState> a -> Optional.empty();
                case Lookup.Unavailable<ProposalState> u ->
                        throw new LedgerStateUnavailableException("proposal " + id + ": " + u.reason());
            };
        }

        /**
         * {@code proposalsAddAction}: an action without a lineage is always added; one with a lineage when its parent
         * is the purpose's root or a node of the purpose's graph (a proposal of the same purpose).
         *
         * @return false when the parent is invalid (the proposal is not added)
         */
        boolean add(Action action, GovActionId parent) {
            GovPurpose purpose = action.purpose();
            if (purpose != null && !Objects.equals(parent, roots().root(purpose))) {
                if (parent == null) {
                    return false;
                }
                Optional<Action> node = lookup(parent);
                if (node.isEmpty() || node.get().purpose() != purpose) {
                    return false;
                }
            }
            added.put(action.id(), action);
            return true;
        }

        private static Action of(ProposalState state) {
            int tag = tagOf(state.type());
            ProtVer version = null;
            if (tag == RawProposal.HARD_FORK_INITIATION && state.action() instanceof HardForkInitiationAction hf
                    && hf.getProtocolVersion() != null) {
                version = new ProtVer(hf.getProtocolVersion().getMajor(), hf.getProtocolVersion().getMinor());
            }
            return new Action(state.id(), tag, state.expiresAfterEpoch(), version, state.anyInSecurityGroup());
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static int tagOf(GovActionType type) {
        return switch (type) {
            case PARAMETER_CHANGE_ACTION -> RawProposal.PARAMETER_CHANGE;
            case HARD_FORK_INITIATION_ACTION -> RawProposal.HARD_FORK_INITIATION;
            case TREASURY_WITHDRAWALS_ACTION -> RawProposal.TREASURY_WITHDRAWALS;
            case NO_CONFIDENCE -> RawProposal.NO_CONFIDENCE;
            case UPDATE_COMMITTEE -> RawProposal.UPDATE_COMMITTEE;
            case NEW_CONSTITUTION -> RawProposal.NEW_CONSTITUTION;
            case INFO_ACTION -> RawProposal.INFO;
        };
    }

    static GovPurpose purposeOf(int actionTag) {
        return switch (actionTag) {
            case RawProposal.PARAMETER_CHANGE -> GovPurpose.PPARAM_UPDATE;
            case RawProposal.HARD_FORK_INITIATION -> GovPurpose.HARD_FORK;
            case RawProposal.NO_CONFIDENCE, RawProposal.UPDATE_COMMITTEE -> GovPurpose.COMMITTEE;
            case RawProposal.NEW_CONSTITUTION -> GovPurpose.CONSTITUTION;
            default -> null;
        };
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
