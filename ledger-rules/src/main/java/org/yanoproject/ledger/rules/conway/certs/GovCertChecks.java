package org.yanoproject.ledger.rules.conway.certs;

import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Conway {@code GOVCERT} ({@code conwayGovCertTransition}, Conway/Rules/GovCert.hs:180-276), one scope per
 * certificate kind:
 *
 * <ul>
 *   <li>{@code ConwayRegDRep} (tag 16; :210-232): {@code ConwayDRepAlreadyRegistered}, then
 *       {@code ConwayDRepIncorrectDeposit} against {@code ppDRepDeposit}.</li>
 *   <li>{@code ConwayUnRegDRep} (tag 17; :234-255): {@code ConwayDRepNotRegistered} (state unchanged), then
 *       {@code ConwayDRepIncorrectRefund} against the recorded deposit (registered DReps only).</li>
 *   <li>{@code ConwayUpdateDRep} (tag 18; :256-272): {@code ConwayDRepNotRegistered} (state unchanged).</li>
 *   <li>{@code ConwayAuthCommitteeHotKey} and {@code ConwayResignCommitteeColdKey} (tags 14, 15; :185-209,
 *       273-276, {@code checkAndOverwriteCommitteeMemberState}): {@code ConwayCommitteeHasPreviouslyResigned} when the
 *       running committee state records a resignation for the cold credential (also one earlier in the same
 *       transaction), then {@code ConwayCommitteeIsUnknown} unless the cold credential is a member of the current
 *       committee or is added by an {@code UpdateCommittee} proposal already in {@code Proposals}. The latter is judged
 *       against the state before the transaction: the committee changes only at an epoch boundary, and
 *       {@code committeeProposals} is taken before {@code GOV} adds this transaction's proposals (Ledger.hs:367-370).</li>
 * </ul>
 */
public final class GovCertChecks {

    private GovCertChecks() {
    }

    /** :210-212. */
    public static final class DRepAlreadyRegistered extends PredicateCheck<CertSubject> {

        public DRepAlreadyRegistered() {
            super(ConwayPredicate.CONWAY_DREP_ALREADY_REGISTERED);
        }

        @Override
        protected String detail(CertSubject s) {
            return s.drep().isPresent() ? s.credential().toString() : null;
        }
    }

    /** :213-219: the stated deposit against {@code ppDRepDeposit}. */
    public static final class DRepIncorrectDeposit extends PredicateCheck<CertSubject> {

        public DRepIncorrectDeposit() {
            super(ConwayPredicate.CONWAY_DREP_INCORRECT_DEPOSIT);
        }

        @Override
        protected String detail(CertSubject s) {
            BigInteger expected = s.params().drepDeposit();
            return s.raw().coin().equals(expected) ? null : DelegChecks.mismatch(s.raw().coin(), expected);
        }
    }

    /** :241 ({@code ConwayUnRegDRep}), 257-258 ({@code ConwayUpdateDRep}): the DRep must be registered. */
    public static final class DRepNotRegistered extends PredicateCheck<CertSubject> {

        public DRepNotRegistered() {
            super(ConwayPredicate.CONWAY_DREP_NOT_REGISTERED);
        }

        @Override
        protected String detail(CertSubject s) {
            return s.drep().isPresent() ? null : s.credential().toString();
        }
    }

    /** :236-242 ({@code failOnJust}): a registered DRep's refund against its recorded deposit. */
    public static final class DRepIncorrectRefund extends PredicateCheck<CertSubject> {

        public DRepIncorrectRefund() {
            super(ConwayPredicate.CONWAY_DREP_INCORRECT_REFUND);
        }

        @Override
        protected String detail(CertSubject s) {
            Optional<DRepState> drep = s.drep();
            return drep.isEmpty() || s.raw().coin().equals(drep.get().deposit()) ? null
                    : DelegChecks.mismatch(s.raw().coin(), drep.get().deposit());
        }
    }

    /** :190-196 ({@code failOnJust}): the cold credential has not resigned (running committee state). */
    public static final class CommitteeHasPreviouslyResigned extends PredicateCheck<CertSubject> {

        public CommitteeHasPreviouslyResigned() {
            super(ConwayPredicate.CONWAY_COMMITTEE_HAS_PREVIOUSLY_RESIGNED);
        }

        @Override
        protected String detail(CertSubject s) {
            CredentialKey cold = s.credential();
            return s.state().committeeMemberByCold(cold).orElseThrowUnavailable()
                    .filter(CommitteeMemberState::resigned).isPresent() ? cold.toString() : null;
        }
    }

    /**
     * :197-205: the cold credential is a member of the current committee or a potential future member (a pending
     * {@code UpdateCommittee} proposal, the pre-transaction proposals, Ledger.hs:367-370).
     */
    public static final class CommitteeIsUnknown extends PredicateCheck<CertSubject> {

        public CommitteeIsUnknown() {
            super(ConwayPredicate.CONWAY_COMMITTEE_IS_UNKNOWN);
        }

        @Override
        protected String detail(CertSubject s) {
            CredentialKey cold = s.credential();
            LedgerView before = s.ctx().preState();
            boolean currentMember = before.committeeMemberByCold(cold).orElseThrowUnavailable()
                    .filter(CommitteeMemberState::isElected).isPresent();
            boolean potentialFutureMember = !currentMember
                    && before.committeeCandidates().require("committee candidates").contains(cold);
            return currentMember || potentialFutureMember ? null : cold.toString();
        }
    }
}
