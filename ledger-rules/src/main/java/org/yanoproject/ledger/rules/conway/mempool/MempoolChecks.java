package org.yanoproject.ledger.rules.conway.mempool;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.CheckLabel;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.RuleUnit;
import org.yanoproject.ledger.rules.conway.ruleset.UnitKind;
import org.yanoproject.ledger.rules.view.model.CredentialKey;

import java.util.List;
import java.util.Set;

/**
 * The {@code MEMPOOL} rule's own checks ({@code mempoolTransition}, Conway/Rules/Mempool.hs:103-138), in
 * {@link MempoolRule}'s order. Both report {@code ConwayMempoolFailure}, a {@code ConwayLedgerPredFailure} constructor.
 */
public final class MempoolChecks {

    private MempoolChecks() {
    }

    /** A {@code ConwayMempoolFailure} check (its text is the constructor's only field). */
    private abstract static class MempoolFailureCheck implements RuleUnit<MempoolSubject> {

        private final String id;
        private final String haskellRef;

        MempoolFailureCheck(String suffix, String haskellRef) {
            this.id = ConwayPredicate.CONWAY_MEMPOOL_FAILURE.qualifiedName() + "#" + suffix;
            this.haskellRef = haskellRef;
        }

        /** @return the failure's text, or null when the check holds */
        abstract String text(MempoolSubject subject);

        @Override
        public final List<LedgerFailure> apply(MempoolSubject subject) {
            String text = text(subject);
            return text == null ? List.of() : List.of(MempoolRule.failure(text));
        }

        @Override
        public final String id() {
            return id;
        }

        @Override
        public final UnitKind kind() {
            return UnitKind.CHECK;
        }

        @Override
        public final CheckLabel label() {
            return ConwayPredicate.CONWAY_MEMPOOL_FAILURE.label();
        }

        @Override
        public final String haskellRef() {
            return haskellRef;
        }

        @Override
        public final List<ConwayPredicate> reports() {
            return List.of(ConwayPredicate.CONWAY_MEMPOOL_FAILURE);
        }
    }

    /**
     * {@code notAllSpent = any (`Map.member` utxo) inputs} (Mempool.hs:113-118): at least one spending input must be
     * unspent. Its failure stops everything ({@code whenFailureFreeDefault}), {@code LEDGER} included.
     */
    public static final class AllInputsSpent extends MempoolFailureCheck {

        public AllInputsSpent() {
            super("allInputsSpent", "Conway/Rules/Mempool.hs:113-118 (any (`Map.member` utxo) inputs; "
                    + "whenFailureFreeDefault)");
        }

        @Override
        String text(MempoolSubject subject) {
            return MempoolRule.anySpendingInputUnspent(subject.body(), subject.incoming()) ? null
                    : MempoolRule.ALL_INPUTS_SPENT;
        }

        @Override
        public boolean haltsOnFailure() {
            return true;
        }
    }

    /**
     * {@code unelectedCommitteeVoters} while {@code hardforkConwayDisallowUnelectedCommitteeFromVoting} is off
     * (Mempool.hs:120-138, Conway/Era.hs:262-263): committee voters that no elected member has authorised. From
     * protocol version 11 {@code GOV} checks it instead ({@code GOV.UnelectedCommitteeVoters}). Its failure does not stop
     * {@code LEDGER}.
     */
    public static final class UnelectedCommitteeVoters extends MempoolFailureCheck {

        public UnelectedCommitteeVoters() {
            super("unelectedCommitteeVoters", "Conway/Rules/Mempool.hs:120-138 (unless "
                    + "hardforkConwayDisallowUnelectedCommitteeFromVoting: failOnNonEmpty unelectedCommitteeVoters); "
                    + "Gov.hs:652-665");
        }

        @Override
        String text(MempoolSubject subject) {
            Set<CredentialKey> unelected = MempoolRule.unelectedCommitteeVoters(subject.body().getVotingProcedures(),
                    subject.incoming());
            return unelected.isEmpty() ? null
                    : MempoolRule.UNELECTED_COMMITTEE_VOTERS_PREFIX + MempoolRule.showCredentials(unelected);
        }
    }
}
