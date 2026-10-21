package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Conway {@code DELEG} ({@code conwayDelegTransition}, Conway/Rules/Deleg.hs:187-301), one scope per certificate kind:
 *
 * <ul>
 *   <li>{@code ConwayRegCert} (tags 0, 7; :233-239): the stated deposit (tag 7 only) against {@code ppKeyDeposit},
 *       then {@code StakeKeyRegisteredDELEG}.</li>
 *   <li>{@code ConwayUnRegCert} (tags 1, 8; :240-278): the stated refund (tag 8 only, and only for a registered
 *       credential) against the recorded deposit, then {@code StakeKeyHasNonZeroAccountBalanceDELEG}, then
 *       {@code StakeKeyNotRegisteredDELEG} (which leaves the state unchanged).</li>
 *   <li>{@code ConwayDelegCert} (tags 2, 9, 10; :279-292): the delegatee (pool, then DRep), then
 *       {@code StakeKeyNotRegisteredDELEG} (state unchanged).</li>
 *   <li>{@code ConwayRegDelegCert} (tags 11–13; :293-301): deposit, {@code StakeKeyRegisteredDELEG}, delegatee.</li>
 * </ul>
 *
 * <p>Deposit and refund mismatches are {@code IncorrectDepositDELEG} before protocol version 11 and
 * {@code DepositIncorrectDELEG} / {@code RefundIncorrectDELEG} from 11
 * ({@code hardforkConwayDELEGIncorrectDepositsAndRefunds}: the protocol version 11 delta supersedes the two units). A
 * DRep delegatee must be registered from protocol version 10 ({@code hardforkConwayBootstrapPhase}: the protocol version
 * 10 delta adds the unit); the two predefined DReps always pass. Pools and DReps are those of the running state, so a
 * pool or DRep registered earlier in the same transaction counts.</p>
 *
 * <p>The other bootstrap-phase difference of {@code DELEG}, {@code preserveIncorrectDelegation} (Deleg.hs:288, 298,
 * 348-372: before protocol version 10 a re-delegation to a DRep credential does not remove the old DRep's reverse
 * entry), changes only the DRep reverse index, which no predicate reads; see {@code OverlayLedgerView}.</p>
 */
public final class DelegChecks {

    private DelegChecks() {
    }

    /** @return the stated deposit, or null for a certificate without one (tag 0, {@code StakeRegistration}) */
    private static BigInteger statedDeposit(CertSubject s) {
        return s.raw().tag() == RawCertificate.STAKE_REGISTRATION ? null : s.raw().coin();
    }

    /** @return the stated refund when it is judged (tag 8, registered credential) and wrong, else null */
    private static BigInteger wrongRefund(CertSubject s) {
        Optional<AccountState> account = s.account();
        if (s.raw().tag() == RawCertificate.UNREG && account.isPresent()
                && !s.raw().coin().equals(account.get().deposit())) {
            return s.raw().coin();
        }
        return null;
    }

    /**
     * {@code checkDepositAgainstPParams} (:199-211) before protocol version 11: {@code IncorrectDepositDELEG} with the
     * supplied deposit.
     */
    public static final class IncorrectDeposit extends PredicateCheck<CertSubject> {

        public IncorrectDeposit() {
            super(ConwayPredicate.INCORRECT_DEPOSIT_DELEG, "deposit", "Conway/Rules/Deleg.hs:199-211 "
                    + "(checkDepositAgainstPParams); PV ≤ 10: not hardforkConwayDELEGIncorrectDepositsAndRefunds");
        }

        @Override
        protected String detail(CertSubject s) {
            BigInteger deposit = statedDeposit(s);
            return deposit == null || deposit.equals(s.params().keyDeposit()) ? null : CertsRule.coin(deposit);
        }
    }

    /** {@code checkDepositAgainstPParams} (:199-211) from protocol version 11: {@code DepositIncorrectDELEG}. */
    public static final class DepositIncorrect extends PredicateCheck<CertSubject> {

        public DepositIncorrect() {
            super(ConwayPredicate.DEPOSIT_INCORRECT_DELEG);
        }

        @Override
        protected String detail(CertSubject s) {
            BigInteger deposit = statedDeposit(s);
            BigInteger expected = s.params().keyDeposit();
            return deposit == null || deposit.equals(expected) ? null : mismatch(deposit, expected);
        }
    }

    /** {@code checkStakeKeyNotRegistered} (:212-214). */
    public static final class StakeKeyRegistered extends PredicateCheck<CertSubject> {

        public StakeKeyRegistered() {
            super(ConwayPredicate.STAKE_KEY_REGISTERED_DELEG);
        }

        @Override
        protected String detail(CertSubject s) {
            return s.account().isPresent() ? s.credential().toString() : null;
        }
    }

    /**
     * {@code failOnJust checkInvalidRefund} (:242-259) before protocol version 11: {@code IncorrectDepositDELEG} with
     * the supplied refund; only a registered credential's refund is judged.
     */
    public static final class IncorrectRefund extends PredicateCheck<CertSubject> {

        public IncorrectRefund() {
            super(ConwayPredicate.INCORRECT_DEPOSIT_DELEG, "refund", "Conway/Rules/Deleg.hs:242-259 "
                    + "(checkInvalidRefund); PV ≤ 10: not hardforkConwayDELEGIncorrectDepositsAndRefunds");
        }

        @Override
        protected String detail(CertSubject s) {
            BigInteger refund = wrongRefund(s);
            return refund == null ? null : CertsRule.coin(refund);
        }
    }

    /** {@code failOnJust checkInvalidRefund} (:242-259) from protocol version 11: {@code RefundIncorrectDELEG}. */
    public static final class RefundIncorrect extends PredicateCheck<CertSubject> {

        public RefundIncorrect() {
            super(ConwayPredicate.REFUND_INCORRECT_DELEG);
        }

        @Override
        protected String detail(CertSubject s) {
            BigInteger refund = wrongRefund(s);
            return refund == null ? null : mismatch(refund, s.account().orElseThrow().deposit());
        }
    }

    /** {@code failOnJust checkStakeKeyHasZeroRewardBalance} (:260-268). */
    public static final class NonZeroAccountBalance extends PredicateCheck<CertSubject> {

        public NonZeroAccountBalance() {
            super(ConwayPredicate.STAKE_KEY_HAS_NON_ZERO_ACCOUNT_BALANCE_DELEG);
        }

        @Override
        protected String detail(CertSubject s) {
            Optional<AccountState> account = s.account();
            return account.isPresent() && account.get().rewardBalance().signum() != 0
                    ? CertsRule.coin(account.get().rewardBalance()) : null;
        }
    }

    /**
     * {@code StakeKeyNotRegisteredDELEG} (:271 for {@code ConwayUnRegCert}, :283 for {@code ConwayDelegCert}):
     * {@code failBecause} on a missing account; the state stays unchanged.
     */
    public static final class StakeKeyNotRegistered extends PredicateCheck<CertSubject> {

        public StakeKeyNotRegistered() {
            super(ConwayPredicate.STAKE_KEY_NOT_REGISTERED_DELEG);
        }

        @Override
        protected String detail(CertSubject s) {
            return s.account().isEmpty() ? s.credential().toString() : null;
        }
    }

    /** {@code checkStakeDelegateeRegistered} (:215-219): the delegatee pool must be registered. */
    public static final class DelegateeStakePoolNotRegistered extends PredicateCheck<CertSubject> {

        public DelegateeStakePoolNotRegistered() {
            super(ConwayPredicate.DELEGATEE_STAKE_POOL_NOT_REGISTERED_DELEG);
        }

        @Override
        protected String detail(CertSubject s) {
            RawCertificate.Delegatee delegatee = s.raw().delegatee();
            if (delegatee.pool() == null) {
                return null;
            }
            PoolId pool = PoolId.of(delegatee.pool());
            return s.state().pool(pool).orElseThrowUnavailable().isPresent() ? null
                    : "KeyHash " + HexUtil.encodeHexString(delegatee.pool());
        }
    }

    /**
     * {@code checkStakeDelegateeRegistered} (:220-226): a delegatee DRep credential must be registered (the predefined
     * DReps always pass). Skipped while {@code hardforkConwayBootstrapPhase}, so the protocol version 10 delta adds it.
     */
    public static final class DelegateeDRepNotRegistered extends PredicateCheck<CertSubject> {

        public DelegateeDRepNotRegistered() {
            super(ConwayPredicate.DELEGATEE_DREP_NOT_REGISTERED_DELEG);
        }

        @Override
        protected String detail(CertSubject s) {
            RawCertificate.DRep drep = s.raw().delegatee().drep();
            if (drep == null || drep.credential() == null) {
                return null;
            }
            CredentialKey drepCredential = CertState.key(drep.credential());
            return s.state().drep(drepCredential).orElseThrowUnavailable().isPresent() ? null
                    : drepCredential.toString();
        }
    }

    static String mismatch(BigInteger supplied, BigInteger expected) {
        return "Mismatch {mismatchSupplied = Coin " + supplied + ", mismatchExpected = Coin " + expected + "}";
    }
}
