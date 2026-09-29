package org.yanoproject.ledger.rules.conway.gov;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;

import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;
import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.conway.tx.RawVoter;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.GovPurpose;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

/**
 * Conway {@code GOV} ({@code conwayGovTransition}, Conway/Rules/Gov.hs:446-613), run by {@code LEDGER} when
 * {@code isValid = True}, after {@code CERTS} (Ledger.hs:402-421): the protocol version's rule set's units
 * ({@link GovChecks}) in Haskell's order.
 *
 * <ol>
 *   <li>{@link ConwayScopes#GOV}: from protocol version 11 {@code UnelectedCommitteeVoters} (:478-481; before 11 the
 *       {@code MEMPOOL} rule checks it).</li>
 *   <li>{@link ConwayScopes#GOV_PROPOSAL} for each proposal in body order ({@code processProposal}, :483-566): at
 *       protocol version 9 only {@code DisallowedProposalDuringBootstrap}; {@code ProposalCantFollow};
 *       {@code MalformedProposal}; from protocol version 10 {@code ProposalReturnAccountDoesNotExist} and
 *       {@code TreasuryWithdrawalReturnAccountsDoNotExist}; {@code ProposalDepositIncorrect};
 *       {@code ProposalProcedureNetworkIdMismatch}; the per-action checks; then {@code proposalsAddAction} (a state
 *       step: the proposal joins {@code Proposals} — the state before the transaction plus the proposals of this
 *       transaction accepted so far — when its lineage is valid) and {@code InvalidPrevGovActionId}.</li>
 *   <li>{@link ConwayScopes#GOV_VOTES}, when the transaction votes, against the proposals after step 2 (so a vote on
 *       an earlier proposal of the same transaction is a vote on an existing action): {@code VotersDoNotExist}
 *       (post-{@code CERTS} state: committee hot credentials with a current authorisation, registered DReps, registered
 *       pools), {@code GovActionsDoNotExist} (only the votes of known voters), at protocol version 9 only
 *       {@code DisallowedVotesDuringBootstrap}, {@code VotingOnExpiredGovAction} and {@code DisallowedVoters}.</li>
 * </ol>
 *
 * <p>{@code GOV}'s own state change (proposals and votes into {@code Proposals}) is the effects deriver's; this rule
 * only validates. Unavailable reads fail closed ({@link LedgerStateUnavailableException}).</p>
 */
public final class GovRule {

    private GovRule() {
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
        GovSubject subject = new GovSubject(ctx, ctx.certState().current(), new Proposals(ctx.preState()),
                proposalTxId);
        gov.run(ConwayScopes.GOV, subject);
        for (RawProposal proposal : ctx.raw().proposals()) {
            gov.run(ConwayScopes.GOV_PROPOSAL, new ProposalSubject(subject, proposal,
                    new GovActionId(proposalTxId, proposal.index())));
        }
        Map<RawVoter, SortedMap<GovActionId, Integer>> votes = ctx.raw().votes();
        if (!votes.isEmpty()) {
            gov.run(ConwayScopes.GOV_VOTES, votes(subject, votes));
        }
        ledger.subRule(gov);
    }

    /** Sorts the votes (:568-603): unknown voters, unknown actions of known voters, and the known votes. */
    private static VotesSubject votes(GovSubject gov, Map<RawVoter, SortedMap<GovActionId, Integer>> votes) {
        List<RawVoter> unknownVoters = new ArrayList<>();
        List<GovActionId> unknownActions = new ArrayList<>();
        List<Map.Entry<RawVoter, GovAction>> known = new ArrayList<>();
        votes.forEach((voter, ids) -> {
            if (!voterExists(gov.afterCerts(), voter)) {
                unknownVoters.add(voter);
                return;
            }
            for (GovActionId id : ids.keySet()) {
                Optional<GovAction> action = gov.proposals().lookup(id);
                if (action.isPresent()) {
                    known.add(Map.entry(voter, action.get()));
                } else {
                    unknownActions.add(id);
                }
            }
        });
        return new VotesSubject(gov, unknownVoters, unknownActions, known);
    }

    /**
     * {@code internVoter} (:586-590) against the post-CERTS state: a committee hot credential with a current
     * (non-resigned) authorisation ({@code authorizedHotCommitteeCredentials}), a registered DRep, a registered pool.
     */
    private static boolean voterExists(LedgerView state, RawVoter voter) {
        CredentialKey credential = GovChecks.credential(voter);
        return switch (voter.tag()) {
            case 0, 1 -> !state.committeeMembersByHot(credential).require("committee hot credential " + credential)
                    .isEmpty();
            case 2, 3 -> state.drep(credential).orElseThrowUnavailable().isPresent();
            default -> state.pool(new PoolId(credential.hashHex())).orElseThrowUnavailable().isPresent();
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
}
