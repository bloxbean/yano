package org.yanoproject.ledger.rules.conway.certs;

import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Conway {@code GOVCERT} ({@code conwayGovCertTransition}, Conway/Rules/GovCert.hs:180-276).
 *
 * <ul>
 *   <li>{@code ConwayRegDRep} (tag 16; :210-232): {@code ConwayDRepAlreadyRegistered}, then
 *       {@code ConwayDRepIncorrectDeposit} against {@code ppDRepDeposit}.</li>
 *   <li>{@code ConwayUnRegDRep} (tag 17; :234-255): {@code ConwayDRepNotRegistered} (state unchanged), then
 *       {@code ConwayDRepIncorrectRefund} against the recorded deposit (registered DReps only).</li>
 *   <li>{@code ConwayUpdateDRep} (tag 18; :256-272): {@code ConwayDRepNotRegistered} (state unchanged).</li>
 *   <li>{@code ConwayAuthCommitteeHotKey} and {@code ConwayResignCommitteeColdKey} (tags 14, 15; :185-209,
 *       273-276): {@code ConwayCommitteeHasPreviouslyResigned} when the running committee state records a
 *       resignation for the cold credential (also one earlier in the same transaction), then
 *       {@code ConwayCommitteeIsUnknown} unless the cold credential is a member of the current committee or is added
 *       by an {@code UpdateCommittee} proposal already in {@code Proposals}. Both are judged against the state before
 *       the transaction: the committee changes only at an epoch boundary, and {@code committeeProposals} is taken
 *       before {@code GOV} adds this transaction's proposals (Ledger.hs:367-370).</li>
 * </ul>
 */
final class GovCertRule {

    private GovCertRule() {
    }

    /** @return whether the certificate changes the state */
    static boolean apply(RuleFrame frame, RawCertificate cert, LedgerView state) {
        TransitionContext ctx = frame.context();
        CredentialKey credential = CertState.key(cert.credential());
        switch (cert.tag()) {
            case RawCertificate.REG_DREP -> {
                boolean registered = state.drep(credential).orElseThrowUnavailable().isPresent();
                ctx.check(frame, ConwayPredicate.CONWAY_DREP_ALREADY_REGISTERED,
                        () -> registered ? credential.toString() : null);
                BigInteger expected = CertState.params(ctx).drepDeposit();
                ctx.check(frame, ConwayPredicate.CONWAY_DREP_INCORRECT_DEPOSIT,
                        () -> cert.coin().equals(expected) ? null : DelegRule.mismatch(cert.coin(), expected));
                return true;
            }
            case RawCertificate.UNREG_DREP -> {
                Optional<DRepState> drep = state.drep(credential).orElseThrowUnavailable();
                ctx.check(frame, ConwayPredicate.CONWAY_DREP_NOT_REGISTERED,
                        () -> drep.isPresent() ? null : credential.toString());
                ctx.check(frame, ConwayPredicate.CONWAY_DREP_INCORRECT_REFUND,
                        () -> drep.isEmpty() || cert.coin().equals(drep.get().deposit()) ? null
                                : DelegRule.mismatch(cert.coin(), drep.get().deposit()));
                return drep.isPresent();
            }
            case RawCertificate.UPDATE_DREP -> {
                boolean registered = state.drep(credential).orElseThrowUnavailable().isPresent();
                ctx.check(frame, ConwayPredicate.CONWAY_DREP_NOT_REGISTERED,
                        () -> registered ? null : credential.toString());
                return registered;
            }
            case RawCertificate.AUTH_COMMITTEE_HOT, RawCertificate.RESIGN_COMMITTEE_COLD -> {
                checkAndOverwriteCommitteeMemberState(frame, credential, state);
                return true;
            }
            default -> throw new IllegalArgumentException(cert + " is not a GOVCERT certificate");
        }
    }

    /** {@code checkAndOverwriteCommitteeMemberState} (:185-209). */
    private static void checkAndOverwriteCommitteeMemberState(RuleFrame frame, CredentialKey cold, LedgerView state) {
        TransitionContext ctx = frame.context();
        ctx.check(frame, ConwayPredicate.CONWAY_COMMITTEE_HAS_PREVIOUSLY_RESIGNED,
                () -> state.committeeMemberByCold(cold).orElseThrowUnavailable()
                        .filter(CommitteeMemberState::resigned).isPresent() ? cold.toString() : null);
        ctx.check(frame, ConwayPredicate.CONWAY_COMMITTEE_IS_UNKNOWN, () -> {
            LedgerView before = ctx.preState();
            boolean currentMember = before.committeeMemberByCold(cold).orElseThrowUnavailable()
                    .filter(CommitteeMemberState::isElected).isPresent();
            boolean potentialFutureMember = !currentMember
                    && before.committeeCandidates().require("committee candidates").contains(cold);
            return currentMember || potentialFutureMember ? null : cold.toString();
        });
    }
}
