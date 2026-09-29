package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Conway {@code DELEG} ({@code conwayDelegTransition}, Conway/Rules/Deleg.hs:187-301), each certificate's predicates
 * in Haskell's order:
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
 * ({@code hardforkConwayDELEGIncorrectDepositsAndRefunds}). A DRep delegatee must be registered from protocol
 * version 10 ({@code hardforkConwayBootstrapPhase}: {@code DelegateeDRepNotRegisteredDELEG} is
 * {@code PvRange.POST_BOOTSTRAP}); the two predefined DReps always pass. Pools and DReps are those of the running
 * state, so a pool or DRep registered earlier in the same transaction counts.</p>
 *
 * <p>The other bootstrap-phase difference of {@code DELEG}, {@code preserveIncorrectDelegation} (Deleg.hs:288, 298,
 * 348-372: before protocol version 10 a re-delegation to a DRep credential does not remove the old DRep's reverse
 * entry), changes only the DRep reverse index, which no predicate reads; see {@code OverlayLedgerView}.</p>
 */
final class DelegRule {

    private DelegRule() {
    }

    /** @return whether the certificate changes the state (false when Haskell returns its input state) */
    static boolean apply(RuleFrame frame, RawCertificate cert, LedgerView state) {
        TransitionContext ctx = frame.context();
        CredentialKey credential = CertState.key(cert.credential());
        Optional<AccountState> account = state.account(credential).orElseThrowUnavailable();
        switch (cert.tag()) {
            case RawCertificate.STAKE_REGISTRATION, RawCertificate.REG -> {
                if (cert.tag() == RawCertificate.REG) {
                    checkDeposit(frame, cert.coin());
                }
                checkNotRegistered(frame, credential, account);
                return true;
            }
            case RawCertificate.STAKE_DEREGISTRATION, RawCertificate.UNREG -> {
                // failOnJust checkInvalidRefund (:242-259): only a registered credential's refund is judged
                if (cert.tag() == RawCertificate.UNREG && account.isPresent()
                        && !cert.coin().equals(account.get().deposit())) {
                    BigInteger expected = account.get().deposit();
                    ctx.check(frame, ConwayPredicate.INCORRECT_DEPOSIT_DELEG, () -> CertsRule.coin(cert.coin()));
                    ctx.check(frame, ConwayPredicate.REFUND_INCORRECT_DELEG, () -> mismatch(cert.coin(), expected));
                }
                // failOnJust checkStakeKeyHasZeroRewardBalance (:260-268)
                ctx.check(frame, ConwayPredicate.STAKE_KEY_HAS_NON_ZERO_ACCOUNT_BALANCE_DELEG,
                        () -> account.isPresent() && account.get().rewardBalance().signum() != 0
                                ? CertsRule.coin(account.get().rewardBalance()) : null);
                if (account.isEmpty()) {
                    ctx.check(frame, ConwayPredicate.STAKE_KEY_NOT_REGISTERED_DELEG, credential::toString);
                    return false;
                }
                return true;
            }
            case RawCertificate.STAKE_DELEGATION, RawCertificate.VOTE_DELEG, RawCertificate.STAKE_VOTE_DELEG -> {
                checkDelegatee(frame, cert.delegatee(), state);
                if (account.isEmpty()) {
                    ctx.check(frame, ConwayPredicate.STAKE_KEY_NOT_REGISTERED_DELEG, credential::toString);
                    return false;
                }
                return true;
            }
            case RawCertificate.STAKE_REG_DELEG, RawCertificate.VOTE_REG_DELEG, RawCertificate.STAKE_VOTE_REG_DELEG -> {
                checkDeposit(frame, cert.coin());
                checkNotRegistered(frame, credential, account);
                checkDelegatee(frame, cert.delegatee(), state);
                return true;
            }
            default -> throw new IllegalArgumentException(cert + " is not a DELEG certificate");
        }
    }

    /** {@code checkDepositAgainstPParams} (:199-211). */
    private static void checkDeposit(RuleFrame frame, BigInteger deposit) {
        TransitionContext ctx = frame.context();
        BigInteger expected = CertState.params(ctx).keyDeposit();
        if (deposit.equals(expected)) {
            return;
        }
        ctx.check(frame, ConwayPredicate.INCORRECT_DEPOSIT_DELEG, () -> CertsRule.coin(deposit));
        ctx.check(frame, ConwayPredicate.DEPOSIT_INCORRECT_DELEG, () -> mismatch(deposit, expected));
    }

    /** {@code checkStakeKeyNotRegistered} (:212-214). */
    private static void checkNotRegistered(RuleFrame frame, CredentialKey credential, Optional<AccountState> account) {
        frame.context().check(frame, ConwayPredicate.STAKE_KEY_REGISTERED_DELEG,
                () -> account.isPresent() ? credential.toString() : null);
    }

    /** {@code checkStakeDelegateeRegistered} (:215-229): the pool, then the DRep. */
    private static void checkDelegatee(RuleFrame frame, RawCertificate.Delegatee delegatee, LedgerView state) {
        TransitionContext ctx = frame.context();
        if (delegatee.pool() != null) {
            PoolId pool = PoolId.of(delegatee.pool());
            ctx.check(frame, ConwayPredicate.DELEGATEE_STAKE_POOL_NOT_REGISTERED_DELEG,
                    () -> state.pool(pool).orElseThrowUnavailable().isPresent() ? null
                            : "KeyHash " + HexUtil.encodeHexString(delegatee.pool()));
        }
        RawCertificate.DRep drep = delegatee.drep();
        if (drep != null && drep.credential() != null) {
            CredentialKey drepCredential = CertState.key(drep.credential());
            ctx.check(frame, ConwayPredicate.DELEGATEE_DREP_NOT_REGISTERED_DELEG,
                    () -> state.drep(drepCredential).orElseThrowUnavailable().isPresent() ? null
                            : drepCredential.toString());
        }
    }

    static String mismatch(BigInteger supplied, BigInteger expected) {
        return "Mismatch {mismatchSupplied = Coin " + supplied + ", mismatchExpected = Coin " + expected + "}";
    }
}
