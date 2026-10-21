package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.ada;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.balanced;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.run;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.spec;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.withdrawal;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.stakeCredential;

/**
 * {@code DELEG} (Conway/Rules/Deleg.hs:187-301): every constructor with Haskell's whole failure list, the PV gates
 * ({@code hardforkConwayDELEGIncorrectDepositsAndRefunds} at 11) and the order of several failures of one certificate.
 * World: {@code dev-42} unregistered; {@code dev-77} registered with balance 0; {@code dev-bb} registered with balance
 * 5 ADA; {@code dev-77}'s pool and DRep registered.
 */
class DelegRuleTest {

    private static final String POOL_77 = TestKey.DEV_77.keyHash();
    private static final String UNREGISTERED_POOL = TestKey.DEV_42.keyHash();

    @Test
    @Covers("DELEG.IncorrectDepositDELEG")
    @Covers("DELEG.DepositIncorrectDELEG")
    void aRegistrationMustStateTheKeyDeposit() {
        // Haskell charges ppKeyDeposit whatever the certificate states (conwayTotalDepositsTxCerts), so the
        // transaction balances with 2 ADA and the stated 1 ADA is the only fault.
        var wrong = balanced(spec(new RegCert(stakeCredential(TestKey.DEV_42), ada(1))), ada(-2));
        assertThat(run(wrong, 10)).containsExactly("DELEG.IncorrectDepositDELEG");
        assertThat(run(wrong, 11)).containsExactly("DELEG.DepositIncorrectDELEG");

        var right = balanced(spec(new RegCert(stakeCredential(TestKey.DEV_42), ada(2))), ada(-2));
        assertThat(run(right, 10)).containsExactly("Valid");
        assertThat(run(right, 11)).containsExactly("Valid");

        var regDeleg = balanced(spec(new StakeRegDelegCert(stakeCredential(TestKey.DEV_42), POOL_77, BigInteger.ZERO)),
                ada(-2));
        assertThat(run(regDeleg, 10)).containsExactly("DELEG.IncorrectDepositDELEG");
        assertThat(run(regDeleg, 11)).containsExactly("DELEG.DepositIncorrectDELEG");
    }

    @Test
    @Covers("DELEG.IncorrectDepositDELEG")
    @Covers("DELEG.RefundIncorrectDELEG")
    void aDeregistrationMustStateTheRecordedDeposit() {
        // The refund credited is the recorded deposit (2 ADA); the certificate states 1 ADA.
        var wrong = balanced(spec(new UnregCert(stakeCredential(TestKey.DEV_77), ada(1)), TestKey.DEV_77), ada(2));
        assertThat(run(wrong, 10)).containsExactly("DELEG.IncorrectDepositDELEG");
        assertThat(run(wrong, 11)).containsExactly("DELEG.RefundIncorrectDELEG");

        // An unregistered credential's refund is not judged (checkInvalidRefund: accountState <- mAccountState).
        var unregistered = spec(new UnregCert(stakeCredential(TestKey.DEV_42), ada(1)));
        assertThat(run(unregistered, 10)).containsExactly("DELEG.StakeKeyNotRegisteredDELEG");
        assertThat(run(unregistered, 11)).containsExactly("DELEG.StakeKeyNotRegisteredDELEG");
    }

    @Test
    @Covers("DELEG.StakeKeyRegisteredDELEG")
    void aRegisteredCredentialCannotRegisterAgain() {
        var legacy = balanced(spec(new StakeRegistration(stakeCredential(TestKey.DEV_77))), ada(-2));
        assertThat(run(legacy)).containsExactly("DELEG.StakeKeyRegisteredDELEG");
        var conway = balanced(spec(new RegCert(stakeCredential(TestKey.DEV_77), ada(2)), TestKey.DEV_77), ada(-2));
        assertThat(run(conway)).containsExactly("DELEG.StakeKeyRegisteredDELEG");
        var stakeReg = balanced(spec(new StakeRegDelegCert(stakeCredential(TestKey.DEV_77), POOL_77, ada(2)),
                TestKey.DEV_77), ada(-2));
        assertThat(run(stakeReg)).containsExactly("DELEG.StakeKeyRegisteredDELEG");
        var voteReg = balanced(spec(new VoteRegDelegCert(stakeCredential(TestKey.DEV_77), DRep.abstain(), ada(2)),
                TestKey.DEV_77), ada(-2));
        assertThat(run(voteReg)).containsExactly("DELEG.StakeKeyRegisteredDELEG");
        var both = balanced(spec(new StakeVoteRegDelegCert(stakeCredential(TestKey.DEV_77), POOL_77,
                DRep.noConfidence(), ada(2)), TestKey.DEV_77), ada(-2));
        assertThat(run(both)).containsExactly("DELEG.StakeKeyRegisteredDELEG");
    }

    @Test
    @Covers("DELEG.StakeKeyNotRegisteredDELEG")
    void anUnregisteredCredentialCannotDeregisterOrDelegate() {
        assertThat(run(spec(new StakeDeregistration(stakeCredential(TestKey.DEV_42)))))
                .containsExactly("DELEG.StakeKeyNotRegisteredDELEG");
        assertThat(run(spec(new StakeDelegation(stakeCredential(TestKey.DEV_42),
                new StakePoolId(HexUtil.decodeHexString(POOL_77))))))
                .containsExactly("DELEG.StakeKeyNotRegisteredDELEG");
        assertThat(run(spec(new VoteDelegCert(stakeCredential(TestKey.DEV_42), DRep.abstain()))))
                .containsExactly("DELEG.StakeKeyNotRegisteredDELEG");
        // The registered credential delegates.
        assertThat(run(spec(new VoteDelegCert(stakeCredential(TestKey.DEV_77), DRep.abstain()), TestKey.DEV_77)))
                .containsExactly("Valid");
    }

    @Test
    @Covers("DELEG.StakeKeyHasNonZeroAccountBalanceDELEG")
    void aCredentialWithARewardBalanceCannotDeregister() {
        var deregistration = balanced(spec(new UnregCert(stakeCredential(TestKey.DEV_BB), ada(2)), TestKey.DEV_BB),
                ada(2));
        assertThat(run(deregistration)).containsExactly("DELEG.StakeKeyHasNonZeroAccountBalanceDELEG");

        // Withdrawing the whole balance in the same transaction drains it before the first certificate (the CERTS
        // base case), so the deregistration succeeds.
        var drained = balanced(spec(new UnregCert(stakeCredential(TestKey.DEV_BB), ada(2)), TestKey.DEV_BB), ada(7));
        drained.withdrawals.add(withdrawal(TestKey.DEV_BB, ada(5)));
        assertThat(run(drained, 10)).containsExactly("Valid");
        // From 11 the LEDGER rule drains before CERTS (Ledger.hs:383-393); the result is the same.
        assertThat(run(drained, 11)).containsExactly("Valid");
    }

    @Test
    @Covers("DELEG.DelegateeStakePoolNotRegisteredDELEG")
    void theDelegateePoolMustBeRegistered() {
        var unknownPool = spec(new StakeDelegation(stakeCredential(TestKey.DEV_77),
                new StakePoolId(HexUtil.decodeHexString(UNREGISTERED_POOL))), TestKey.DEV_77);
        assertThat(run(unknownPool)).containsExactly("DELEG.DelegateeStakePoolNotRegisteredDELEG");
        var regDeleg = balanced(spec(new StakeRegDelegCert(stakeCredential(TestKey.DEV_42), UNREGISTERED_POOL,
                ada(2))), ada(-2));
        assertThat(run(regDeleg)).containsExactly("DELEG.DelegateeStakePoolNotRegisteredDELEG");
    }

    @Test
    @Covers("DELEG.DelegateeDRepNotRegisteredDELEG")
    void theDelegateeDRepMustBeRegistered() {
        var unknownDRep = spec(new VoteDelegCert(stakeCredential(TestKey.DEV_77),
                DRep.addrKeyHash(TestKey.DEV_42.keyHash())), TestKey.DEV_77);
        assertThat(run(unknownDRep)).containsExactly("DELEG.DelegateeDRepNotRegisteredDELEG");
        var registeredDRep = spec(new VoteDelegCert(stakeCredential(TestKey.DEV_BB),
                DRep.addrKeyHash(TestKey.DEV_77.keyHash())), TestKey.DEV_BB);
        assertThat(run(registeredDRep)).containsExactly("Valid");
        // The two predefined DReps need no registration.
        assertThat(run(spec(new VoteDelegCert(stakeCredential(TestKey.DEV_77), DRep.noConfidence()), TestKey.DEV_77)))
                .containsExactly("Valid");
        var voteReg = balanced(spec(new VoteRegDelegCert(stakeCredential(TestKey.DEV_42),
                DRep.scriptHash(TestKey.DEV_AA.keyHash()), ada(2))), ada(-2));
        assertThat(run(voteReg)).containsExactly("DELEG.DelegateeDRepNotRegisteredDELEG");
    }

    @Test
    @Covers("DELEG.DelegateeDRepNotRegisteredDELEG")
    void aDelegateeDRepNeedsNoRegistrationDuringTheBootstrapPhase() {
        // unless (hardforkConwayBootstrapPhase pv) (Deleg.hs:220-226): not checked at protocol version 9.
        var unknownDRep = spec(new VoteDelegCert(stakeCredential(TestKey.DEV_77),
                DRep.addrKeyHash(TestKey.DEV_42.keyHash())), TestKey.DEV_77);
        assertThat(run(unknownDRep, 9)).containsExactly("Valid");
        assertThat(run(unknownDRep, 10)).containsExactly("DELEG.DelegateeDRepNotRegisteredDELEG");
        var voteReg = balanced(spec(new VoteRegDelegCert(stakeCredential(TestKey.DEV_42),
                DRep.scriptHash(TestKey.DEV_AA.keyHash()), ada(2))), ada(-2));
        assertThat(run(voteReg, 9)).containsExactly("Valid");
        // The pool is still checked.
        var both = spec(new StakeVoteDelegCert(stakeCredential(TestKey.DEV_77), UNREGISTERED_POOL,
                DRep.addrKeyHash(TestKey.DEV_42.keyHash())), TestKey.DEV_77);
        assertThat(run(both, 9)).containsExactly("DELEG.DelegateeStakePoolNotRegisteredDELEG");
        // A re-delegation from one DRep to another, then the old DRep's deregistration, in one PV 9 transaction: the
        // stale reverse entry (preserveIncorrectDelegation, Deleg.hs:363-373) changes no verdict.
        var redelegate = spec(List.of(new RegDRepCert(MutationWorld.credential(TestKey.DEV_BB), MutationWorld.DREP_DEPOSIT,
                        null), new VoteDelegCert(stakeCredential(TestKey.DEV_77),
                        DRep.addrKeyHash(TestKey.DEV_BB.keyHash())),
                new UnregDRepCert(MutationWorld.credential(TestKey.DEV_77), MutationWorld.DREP_DEPOSIT)),
                TestKey.DEV_77, TestKey.DEV_BB);
        assertThat(run(balanced(redelegate, BigInteger.ZERO), 9)).containsExactly("Valid");
    }

    @Test
    void severalFailuresOfOneCertificateKeepHaskellsOrder() {
        // checkStakeDelegateeRegistered: the pool, then the DRep. DELEG -> CERT -> CERTS -> LEDGER reverse the list
        // four times, so LEDGER lists them in execution order.
        var both = spec(new StakeVoteDelegCert(stakeCredential(TestKey.DEV_77), UNREGISTERED_POOL,
                DRep.addrKeyHash(TestKey.DEV_42.keyHash())), TestKey.DEV_77);
        assertThat(run(both)).containsExactly("DELEG.DelegateeStakePoolNotRegisteredDELEG",
                "DELEG.DelegateeDRepNotRegisteredDELEG");

        // ConwayUnRegCert: the refund, then the balance.
        var refundAndBalance = balanced(spec(new UnregCert(stakeCredential(TestKey.DEV_BB), ada(1)), TestKey.DEV_BB),
                ada(2));
        assertThat(run(refundAndBalance)).containsExactly("DELEG.IncorrectDepositDELEG",
                "DELEG.StakeKeyHasNonZeroAccountBalanceDELEG");

        // ConwayRegDelegCert: deposit, registered, delegatee.
        var all = balanced(spec(new StakeVoteRegDelegCert(stakeCredential(TestKey.DEV_77), UNREGISTERED_POOL,
                DRep.addrKeyHash(TestKey.DEV_42.keyHash()), ada(1)), TestKey.DEV_77), ada(-2));
        assertThat(run(all, 11)).containsExactly("DELEG.DepositIncorrectDELEG", "DELEG.StakeKeyRegisteredDELEG",
                "DELEG.DelegateeStakePoolNotRegisteredDELEG", "DELEG.DelegateeDRepNotRegisteredDELEG");
    }

    @Test
    void registrationWithoutADepositNeedsNoWitnessButStillPaysTheDeposit() {
        // Tag 0 has no witness; dev-aa does not sign.
        var legacy = balanced(spec(List.of(new StakeRegistration(stakeCredential(TestKey.DEV_AA)))), ada(-2));
        assertThat(run(legacy)).containsExactly("Valid");
    }
}
