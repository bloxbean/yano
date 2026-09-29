package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.ResignCommitteeColdCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.effects.LedgerChange;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ScriptOutcome;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.ada;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.balanced;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.run;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.spec;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.withdrawal;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.FRESH_VRF;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.MIN_POOL_COST;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.NETWORK;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.credential;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.poolRegistration;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.stakeCredential;

/**
 * {@code CERTS} (Conway/Rules/Certs.hs:204-246): the base case before the first certificate, the certificates in body
 * order against the intra-transaction state, what later certificates see after a failing one, and the order in which
 * the recursion lists failures.
 */
class CertsRuleTest {

    private static final StakePoolId POOL_77 = new StakePoolId(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()));
    private static final StakePoolId POOL_42 = new StakePoolId(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()));

    // ------------------------------------------------------------------ base case

    @Test
    @Covers("CERTS.WithdrawalsNotInRewardsCERTS")
    void withdrawalsMustDrainRegisteredAccountsBeforeProtocolVersion11() {
        TxSpec partial = balanced(spec(List.of(), TestKey.DEV_BB), ada(1));
        partial.withdrawals.add(withdrawal(TestKey.DEV_BB, ada(1)));
        assertThat(run(partial, 10)).containsExactly("CERTS.WithdrawalsNotInRewardsCERTS");

        TxSpec full = balanced(spec(List.of(), TestKey.DEV_BB), ada(5));
        full.withdrawals.add(withdrawal(TestKey.DEV_BB, ada(5)));
        assertThat(run(full, 10)).containsExactly("Valid");

        // No account: invalid even for zero.
        TxSpec missing = spec(List.of(), TestKey.DEV_AA);
        missing.withdrawals.add(withdrawal(TestKey.DEV_AA, ada(0)));
        assertThat(run(missing, 10)).containsExactly("CERTS.WithdrawalsNotInRewardsCERTS");

        // From 11 the check is LEDGER's (ConwayWithdrawalsMissingAccounts / ConwayIncompleteWithdrawals,
        // LedgerPreChecksTest); CERTS' base case is the identity.
        assertThat(run(partial, 11)).containsExactly("LEDGER.ConwayIncompleteWithdrawals");
        assertThat(run(missing, 11)).containsExactly("LEDGER.ConwayWithdrawalsMissingAccounts");
        assertThat(run(full, 11)).containsExactly("Valid");
    }

    @Test
    void theBaseCaseSeesTheAccountsBeforeTheCertificates() {
        // A withdrawal from a credential registered by the same transaction has no account yet.
        TxSpec registerThenWithdraw = balanced(spec(new RegCert(stakeCredential(TestKey.DEV_42), ada(2))), ada(-2));
        registerThenWithdraw.withdrawals.add(withdrawal(TestKey.DEV_42, ada(0)));
        assertThat(run(registerThenWithdraw)).containsExactly("CERTS.WithdrawalsNotInRewardsCERTS");

        // A withdrawal from a credential the same transaction deregisters is judged (and drained) first.
        TxSpec withdrawThenDeregister = balanced(spec(new UnregCert(stakeCredential(TestKey.DEV_BB), ada(2)),
                TestKey.DEV_BB), ada(7));
        withdrawThenDeregister.withdrawals.add(withdrawal(TestKey.DEV_BB, ada(5)));
        assertThat(run(withdrawThenDeregister)).containsExactly("Valid");
    }

    // ------------------------------------------------------------------ intra-transaction state

    @Test
    void registerThenDelegateOrDeregister() {
        List<Certificate> registerDelegate = List.of(new RegCert(stakeCredential(TestKey.DEV_42), ada(2)),
                new StakeDelegation(stakeCredential(TestKey.DEV_42), POOL_77));
        assertThat(run(balanced(spec(registerDelegate), ada(-2)))).containsExactly("Valid");

        List<Certificate> delegateRegister = List.of(new StakeDelegation(stakeCredential(TestKey.DEV_42), POOL_77),
                new RegCert(stakeCredential(TestKey.DEV_42), ada(2)));
        assertThat(run(balanced(spec(delegateRegister), ada(-2)))).containsExactly("DELEG.StakeKeyNotRegisteredDELEG");

        // The refund of a credential registered earlier in the transaction is ppKeyDeposit (UTXO); CERTS sees it
        // registered.
        List<Certificate> registerDeregister = List.of(new RegCert(stakeCredential(TestKey.DEV_42), ada(2)),
                new UnregCert(stakeCredential(TestKey.DEV_42), ada(2)));
        assertThat(run(spec(registerDeregister))).containsExactly("Valid");
    }

    @Test
    void deregisterThenReregisterOrDelegate() {
        List<Certificate> reregister = List.of(new UnregCert(stakeCredential(TestKey.DEV_77), ada(2)),
                new RegCert(stakeCredential(TestKey.DEV_77), ada(2)));
        assertThat(run(spec(reregister, TestKey.DEV_77))).containsExactly("Valid");

        // Scenarios 00100/00101: a delegation after the deregistration in the same transaction.
        List<Certificate> delegateAfter = List.of(new StakeDeregistration(stakeCredential(TestKey.DEV_77)),
                new StakeDelegation(stakeCredential(TestKey.DEV_77), POOL_77));
        assertThat(run(balanced(spec(delegateAfter, TestKey.DEV_77), ada(2))))
                .containsExactly("DELEG.StakeKeyNotRegisteredDELEG");
    }

    @Test
    void aDRepOrPoolRegisteredEarlierInTheTransactionIsADelegatee() {
        List<Certificate> drepThenDelegate = List.of(new RegDRepCert(credential(TestKey.DEV_42), ada(500), null),
                new VoteDelegCert(stakeCredential(TestKey.DEV_77), DRep.addrKeyHash(TestKey.DEV_42.keyHash())));
        assertThat(run(balanced(spec(drepThenDelegate, TestKey.DEV_77), ada(-500)))).containsExactly("Valid");
        assertThat(run(balanced(spec(drepThenDelegate.reversed(), TestKey.DEV_77), ada(-500))))
                .containsExactly("DELEG.DelegateeDRepNotRegisteredDELEG");

        // A DRep deregistered earlier in the transaction is no delegatee.
        List<Certificate> drepGone = List.of(new UnregDRepCert(credential(TestKey.DEV_77), ada(500)),
                new VoteDelegCert(stakeCredential(TestKey.DEV_BB), DRep.addrKeyHash(TestKey.DEV_77.keyHash())));
        assertThat(run(balanced(spec(drepGone, TestKey.DEV_77, TestKey.DEV_BB), ada(500))))
                .containsExactly("DELEG.DelegateeDRepNotRegisteredDELEG");

        List<Certificate> poolThenDelegate = List.of(
                poolRegistration(TestKey.DEV_42, FRESH_VRF, MIN_POOL_COST, NETWORK, null),
                new StakeDelegation(stakeCredential(TestKey.DEV_77), POOL_42));
        assertThat(run(balanced(spec(poolThenDelegate, TestKey.DEV_77), ada(-500)))).containsExactly("Valid");
        assertThat(run(balanced(spec(poolThenDelegate.reversed(), TestKey.DEV_77), ada(-500))))
                .containsExactly("DELEG.DelegateeStakePoolNotRegisteredDELEG");

        // A retiring pool is still registered.
        List<Certificate> retiringPool = List.of(new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()), 3),
                new StakeDelegation(stakeCredential(TestKey.DEV_BB), POOL_77));
        assertThat(run(spec(retiringPool, TestKey.DEV_77, TestKey.DEV_BB))).containsExactly("Valid");
    }

    @Test
    void aResignationEarlierInTheTransactionIsSeen() {
        // Scenario 00208: resign, then authorize a hot key.
        List<Certificate> resignThenAuthorize = List.of(new ResignCommitteeColdCert(credential(TestKey.DEV_77), null),
                new AuthCommitteeHotCert(credential(TestKey.DEV_77), credential(TestKey.DEV_AA)));
        assertThat(run(spec(resignThenAuthorize, TestKey.DEV_77)))
                .containsExactly("GOVCERT.ConwayCommitteeHasPreviouslyResigned");
        assertThat(run(spec(resignThenAuthorize.reversed(), TestKey.DEV_77))).containsExactly("Valid");
    }

    // ------------------------------------------------------------------ after a failure

    @Test
    void laterCertificatesSeeTheStateAFailingCertificateLeft() {
        // A deregistration of an unregistered credential leaves the state unchanged; the registration after it
        // succeeds.
        List<Certificate> unchanged = List.of(new StakeDeregistration(stakeCredential(TestKey.DEV_42)),
                new RegCert(stakeCredential(TestKey.DEV_42), ada(2)));
        assertThat(run(balanced(spec(unchanged), ada(-2)))).containsExactly("DELEG.StakeKeyNotRegisteredDELEG");

        // A failing registration of a registered credential still registers it afresh (registerConwayAccount
        // overwrites the account, balance 0): the deregistration after it sees no reward balance.
        List<Certificate> overwritten = List.of(new RegCert(stakeCredential(TestKey.DEV_BB), ada(2)),
                new UnregCert(stakeCredential(TestKey.DEV_BB), ada(2)));
        assertThat(run(spec(overwritten, TestKey.DEV_BB))).containsExactly("DELEG.StakeKeyRegisteredDELEG");
    }

    @Test
    void theRecursionListsFailuresInHaskellsOrder() {
        // CERTS (gamma :|> c) = trans CERTS gamma, then trans CERT c. With a base-case failure W and failing
        // certificates A, B, C, small-steps builds F0 = [W], Fk = reverse(CERT k) ++ reverse(F(k-1)), and LEDGER
        // reverses F3 once more: [B, W, A, C].
        TxSpec spec = balanced(spec(List.of(
                new StakeDeregistration(stakeCredential(TestKey.DEV_42)),                       // A
                new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()), 1),         // B
                new AuthCommitteeHotCert(credential(TestKey.DEV_42), credential(TestKey.DEV_AA))), // C
                TestKey.DEV_BB), ada(1));
        spec.withdrawals.add(withdrawal(TestKey.DEV_BB, ada(1)));                                // W
        assertThat(run(spec)).containsExactly("POOL.StakePoolNotRegisteredOnKeyPOOL",
                "CERTS.WithdrawalsNotInRewardsCERTS", "DELEG.StakeKeyNotRegisteredDELEG",
                "GOVCERT.ConwayCommitteeIsUnknown");

        // Two certificates without a base-case failure: execution order.
        TxSpec two = spec(List.of(new StakeDeregistration(stakeCredential(TestKey.DEV_42)),
                new PoolRetirement(HexUtil.decodeHexString(TestKey.DEV_42.keyHash()), 1)));
        assertThat(run(two)).containsExactly("DELEG.StakeKeyNotRegisteredDELEG", "POOL.StakePoolNotRegisteredOnKeyPOOL");
    }

    @Test
    void utxowFailuresComeBeforeCertsFailures() {
        // Scenarios 00102/00103 in miniature: LEDGER runs CERTS before UTXOW, and small-steps prepends each sub-rule's
        // failures, so UTXOW's come first. dev-aa does not sign its deregistration.
        TxSpec spec = balanced(spec(new UnregCert(stakeCredential(TestKey.DEV_AA), ada(2))), ada(2));
        // UTXOW's own failures, then UTXO's (nested in UTXOW), then CERTS'. The refund of an unregistered credential
        // is 0 (shelleyTotalRefundsTxCerts), so the stated 2 ADA does not balance either.
        assertThat(run(spec)).containsExactly("UTXOW.MissingVKeyWitnessesUTXOW", "UTXO.ValueNotConservedUTxO",
                "DELEG.StakeKeyNotRegisteredDELEG");
    }

    @Test
    void anUnavailableReadRejectsTheTransaction() {
        // Invariant 2: a certificate rule that cannot read the state fails closed, never admits.
        for (InMemoryLedgerView.Area area : List.of(InMemoryLedgerView.Area.ACCOUNTS, InMemoryLedgerView.Area.POOLS,
                InMemoryLedgerView.Area.DREPS, InMemoryLedgerView.Area.COMMITTEE)) {
            InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams()).unavailable(area).build();
            TxSpec spec = balanced(spec(List.of(new RegCert(stakeCredential(TestKey.DEV_42), ada(2)),
                    new StakeDelegation(stakeCredential(TestKey.DEV_42), POOL_77),
                    new VoteDelegCert(stakeCredential(TestKey.DEV_42), DRep.addrKeyHash(TestKey.DEV_77.keyHash())),
                    new AuthCommitteeHotCert(credential(TestKey.DEV_77), credential(TestKey.DEV_AA))), TestKey.DEV_77),
                    ada(-2));
            assertThat(run(spec, view, 10)).as("%s unavailable", area).containsExactly("ENGINE.LedgerStateUnavailable");
        }
    }

    // ------------------------------------------------------------------ is_valid, effects

    @Test
    void certificatesDoNotRunForAPhase2InvalidTransaction() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.isValid = false;
        spec.certs.add(new StakeDeregistration(stakeCredential(TestKey.DEV_42)));
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.result = new ScriptPhaseResult.Failed(List.of(new ScriptOutcome("spend", 1, false, 0, 0, List.of(),
                "error")));
        byte[] cbor = ConwayTxBuilder.build(spec, MutationWorld.view()).cbor();
        assertThat(EngineTestSupport.validate(evaluator, cbor)).isInstanceOf(TxValidationOutcome.Valid.class);
        spec.isValid = true;
        byte[] valid = ConwayTxBuilder.build(spec, MutationWorld.view()).cbor();
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), valid)))
                .containsExactly("DELEG.StakeKeyNotRegisteredDELEG");
    }

    @Test
    void theEffectsFollowTheSameIntermediateState() {
        List<Certificate> certs = List.of(new StakeRegistration(stakeCredential(TestKey.DEV_42)),
                new StakeDelegation(stakeCredential(TestKey.DEV_42), POOL_77),
                new VoteDelegCert(stakeCredential(TestKey.DEV_42), DRep.abstain()));
        TxValidationOutcome outcome = CertTestSupport.validate(balanced(spec(certs), ada(-2)), MutationWorld.view(), 10);
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Valid.class);
        assertThat(((TxValidationOutcome.Valid) outcome).effects().changes()).map(Object::getClass)
                .containsExactly(LedgerChange.AccountRegistered.class, LedgerChange.StakeDelegated.class,
                        LedgerChange.VoteDelegated.class);
    }
}
