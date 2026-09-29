package org.yanoproject.ledger.rules.conway.certs;

import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
import org.yanoproject.ledger.rules.conway.ruleset.StateStep;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;

/**
 * The {@code CERTS} base case's units ({@code ConwayScopes.CERTS}, Conway/Rules/Certs.hs:223-241), the step that
 * {@code LEDGER} takes instead from protocol version 11 (Ledger.hs:387-392), and a certificate's own state step
 * ({@code ConwayScopes.CERT}).
 */
public final class CertsChecks {

    private CertsChecks() {
    }

    /**
     * The base case's predicate before protocol version 11 (Certs.hs:222-236): {@code WithdrawalsNotInRewardsCERTS},
     * against the incoming accounts, carries the missing and the incomplete withdrawals (the latter with their supplied
     * amounts) in one failure.
     */
    public static final class WithdrawalsNotInRewards extends PredicateCheck<TransitionContext> {

        public WithdrawalsNotInRewards() {
            super(ConwayPredicate.WITHDRAWALS_NOT_IN_REWARDS);
        }

        @Override
        protected String detail(TransitionContext ctx) {
            CertsRule.UndrainedWithdrawals undrained = CertsRule.withdrawalsThatDoNotDrainAccounts(ctx,
                    ctx.certState().current());
            if (undrained.isEmpty()) {
                return null;
            }
            return "Withdrawals {missing or wrong network: " + undrained.missing() + ", incomplete: "
                    + undrained.incomplete() + "}";
        }
    }

    /**
     * The step before the first certificate: {@code updateDormantDRepExpiries}, {@code updateVotingDRepExpiries} and
     * {@code drainAccounts}, applied to {@link TransitionContext#certState()} ({@link CertState#advancePreCertificate}).
     * The {@code CERTS} base case takes it before protocol version 11 ({@code CERTS.preCertificateStep}), {@code LEDGER}
     * from 11 ({@code LEDGER.preCertificateStep}, {@code hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule}).
     */
    public static final class PreCertificateStep extends StateStep<TransitionContext> {

        /** {@code CERTS.preCertificateStep}: the base case's step (Certs.hs:237-241). */
        public static PreCertificateStep inCerts() {
            return new PreCertificateStep("CERTS.preCertificateStep", "Conway/Rules/Certs.hs:237-241 "
                    + "(updateDormantDRepExpiries, updateVotingDRepExpiries, drainAccounts)");
        }

        /** {@code LEDGER.preCertificateStep}: {@code LEDGER}'s step (Ledger.hs:387-392). */
        public static PreCertificateStep inLedger() {
            return new PreCertificateStep("LEDGER.preCertificateStep", "Conway/Rules/Ledger.hs:387-392 "
                    + "(hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule: updateDormantDRepExpiries, "
                    + "updateVotingDRepExpiries, drainAccounts)");
        }

        private PreCertificateStep(String id, String haskellRef) {
            super(id, haskellRef);
        }

        @Override
        protected void advance(TransitionContext ctx) {
            CertState.advancePreCertificate(ctx);
        }
    }

    /**
     * A certificate's state step ({@code CERT}, Cert.hs:210-223, after {@code DELEG}, {@code POOL} or {@code GOVCERT}):
     * the certificate's changes ({@link CertState#advance}), except where Haskell's transition returns its input state —
     * the deregistration or delegation of an unregistered credential, the retirement of an unregistered pool, the
     * deregistration or update of an unregistered DRep.
     */
    public static final class ApplyCertificate extends StateStep<CertSubject> {

        public ApplyCertificate() {
            super("CERT.applyCertificate", "Conway/Rules/Deleg.hs:233-301, Shelley/Rules/Pool.hs:225-323, "
                    + "Conway/Rules/GovCert.hs:210-276 (each certificate's new state)");
        }

        @Override
        protected void advance(CertSubject s) {
            CertState.advance(s.ctx(), s.certificate(), changesState(s));
        }

        /** @return false when Haskell's transition returns its input state for the certificate */
        private static boolean changesState(CertSubject s) {
            return switch (s.raw().tag()) {
                case RawCertificate.STAKE_DEREGISTRATION, RawCertificate.UNREG, RawCertificate.STAKE_DELEGATION,
                     RawCertificate.VOTE_DELEG, RawCertificate.STAKE_VOTE_DELEG -> s.account().isPresent();
                case RawCertificate.POOL_RETIREMENT -> s.pool().isPresent();
                case RawCertificate.UNREG_DREP, RawCertificate.UPDATE_DREP -> s.drep().isPresent();
                default -> true;
            };
        }
    }
}
