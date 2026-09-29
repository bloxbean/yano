package org.yanoproject.ledger.rules.conway.ledger;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import org.junit.jupiter.api.Test;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.util.HexUtil;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.conway.utxo.MinFee;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The protocol-version-11 {@code LEDGER} withdrawal checks ({@code testIncompleteAndMissingWithdrawals},
 * Conway/Rules/Ledger.hs:383-386, Shelley/Rules/Ledger.hs:351-359) and the pre-certificate step after them. World:
 * {@code dev-bb} registered with a 5 ADA balance, {@code dev-42} and {@code dev-aa} unregistered.
 */
class LedgerPreChecksTest {

    private static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    private static BigInteger ada(long amount) {
        return ADA.multiply(BigInteger.valueOf(amount));
    }

    private static TxSpec withdrawing(TestKey key, Network network,
                                      BigInteger amount) {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.withdrawals.add(new Withdrawal(MutationWorld.rewardAccount(key, network), amount));
        spec.signers.add(key);
        spec.changeAdjust = amount;
        return spec;
    }

    private static List<String> run(TxSpec spec, int protocolMajor) {
        InMemoryLedgerView view = MutationWorld.view(protocolMajor);
        byte[] cbor = ConwayTxBuilder.build(spec, view).cbor();
        return EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), cbor, view,
                MutationWorld.env(protocolMajor), null));
    }

    @Test
    @Covers("LEDGER.ConwayWithdrawalsMissingAccounts")
    void aWithdrawalNeedsARegisteredAccountOnTheLedgersNetwork() {
        // dev-aa has no account, so it has no DRep delegation either: ConwayWdrlNotDelegatedToDRep ran first and is
        // listed last.
        assertThat(run(withdrawing(TestKey.DEV_AA, MutationWorld.NETWORK, BigInteger.ZERO), 11))
                .containsExactly("LEDGER.ConwayWithdrawalsMissingAccounts", "LEDGER.ConwayWdrlNotDelegatedToDRep");
        // Another network counts as missing (categorizeWithdrawals); UTXO reports the network too, first.
        assertThat(run(withdrawing(TestKey.DEV_BB, Networks.mainnet(), ada(5)), 11))
                .containsExactly("UTXO.WrongNetworkWithdrawal", "LEDGER.ConwayWithdrawalsMissingAccounts");
        // Before 11 the same fault is CERTS' WithdrawalsNotInRewardsCERTS.
        assertThat(run(withdrawing(TestKey.DEV_AA, MutationWorld.NETWORK, BigInteger.ZERO), 10))
                .containsExactly("CERTS.WithdrawalsNotInRewardsCERTS", "LEDGER.ConwayWdrlNotDelegatedToDRep");
    }

    @Test
    @Covers("LEDGER.ConwayIncompleteWithdrawals")
    void aWithdrawalDrainsTheWholeBalance() {
        assertThat(run(withdrawing(TestKey.DEV_BB, MutationWorld.NETWORK, ada(1)), 11))
                .containsExactly("LEDGER.ConwayIncompleteWithdrawals");
        assertThat(run(withdrawing(TestKey.DEV_BB, MutationWorld.NETWORK, ada(5)), 11)).containsExactly("Valid");
        assertThat(run(withdrawing(TestKey.DEV_BB, MutationWorld.NETWORK, ada(1)), 10))
                .containsExactly("CERTS.WithdrawalsNotInRewardsCERTS");
    }

    @Test
    void bothChecksInHaskellsOrder() {
        // Two failOnNonEmptyMap predicates of LEDGER: missing, then incomplete; LEDGER's list is the reverse.
        TxSpec spec = withdrawing(TestKey.DEV_BB, MutationWorld.NETWORK, ada(1));
        spec.withdrawals.add(new Withdrawal(MutationWorld.rewardAccount(TestKey.DEV_AA, MutationWorld.NETWORK),
                BigInteger.ZERO));
        spec.signers.add(TestKey.DEV_AA);
        assertThat(run(spec, 11)).containsExactly("LEDGER.ConwayIncompleteWithdrawals",
                "LEDGER.ConwayWithdrawalsMissingAccounts", "LEDGER.ConwayWdrlNotDelegatedToDRep");
    }

    @Test
    void theChecksJudgeTheIncomingAccountsAndTheDrainPrecedesTheCertificates() {
        // dev-bb withdraws its whole balance and deregisters: judged before the certificates, drained before CERTS.
        TxSpec spec = withdrawing(TestKey.DEV_BB, MutationWorld.NETWORK, ada(5));
        spec.certs.add(new UnregCert(MutationWorld.stakeCredential(TestKey.DEV_BB), ada(2)));
        spec.changeAdjust = ada(7);
        assertThat(run(spec, 11)).containsExactly("Valid");
        // Missing-account and certificate failures: LEDGER lists CERTS' before its own earlier predicates.
        TxSpec both = withdrawing(TestKey.DEV_AA, MutationWorld.NETWORK, BigInteger.ZERO);
        both.certs.add(new UnregCert(MutationWorld.stakeCredential(TestKey.DEV_42), ada(2)));
        assertThat(run(both, 11)).containsExactly("DELEG.StakeKeyNotRegisteredDELEG",
                "LEDGER.ConwayWithdrawalsMissingAccounts", "LEDGER.ConwayWdrlNotDelegatedToDRep");
    }

    // ------------------------------------------------------------------ Phase 5: the three LEDGER predicates

    @Test
    @Covers("LEDGER.ConwayTreasuryValueMismatch")
    void aStatedTreasuryValueMustBeTheLedgersTreasury() {
        TxSpec wrong = MutationWorld.simpleSpec();
        wrong.currentTreasuryValue = MutationWorld.TREASURY.add(BigInteger.ONE);
        assertThat(run(wrong, 10)).containsExactly("LEDGER.ConwayTreasuryValueMismatch");
        assertThat(run(wrong, 11)).containsExactly("LEDGER.ConwayTreasuryValueMismatch");
        TxSpec right = MutationWorld.simpleSpec();
        right.currentTreasuryValue = MutationWorld.TREASURY;
        assertThat(run(right, 10)).containsExactly("Valid");
        // Not stated: nothing to check.
        assertThat(run(MutationWorld.simpleSpec(), 10)).containsExactly("Valid");
    }

    @Test
    @Covers("LEDGER.ConwayTxRefScriptsSizeTooBig")
    void referenceScriptsFitThePerTransactionLimit() {
        // 205,000 bytes of reference script on a reference input: over the 200 KiB limit. The fee pays the tiered
        // reference-script fee, so only LEDGER fails.
        TxSpec reference = MutationWorld.simpleSpec();
        reference.referenceInputs.add(MutationWorld.BIG_REFERENCE_SCRIPT_INPUT);
        reference.feeAdjust = bigReferenceScriptFee();
        assertThat(run(reference, 10)).containsExactly("LEDGER.ConwayTxRefScriptsSizeTooBig");
        // A spending input's reference script counts too, and an input both spent and referenced counts once.
        TxSpec spent = MutationWorld.simpleSpec();
        spent.inputs.add(MutationWorld.BIG_REFERENCE_SCRIPT_INPUT);
        spent.feeAdjust = bigReferenceScriptFee();
        assertThat(run(spent, 11)).containsExactly("LEDGER.ConwayTxRefScriptsSizeTooBig");
        // Under a larger limit the same transaction is valid (the limit is a constant, moved only by fixtures).
        InMemoryLedgerView view = MutationWorld.view(10);
        byte[] cbor = ConwayTxBuilder.build(reference, view).cbor();
        ConwayLedgerConstants roomy = ConwayLedgerConstants.HASKELL.with(300_000L, null, null, null, null);
        assertThat(EngineTestSupport.names(EngineTestSupport.validateWith(
                new JavaLedgerValidationEngine(new StubEvaluator()).withConstants(roomy), cbor, view,
                MutationWorld.env(10), TxValidationRequest.Rule.LEDGER))).containsExactly("Valid");
    }

    private static BigInteger bigReferenceScriptFee() {
        return MinFee.tierRefScriptFee(ConwayLedgerConstants.HASKELL,
                MutationWorld.protocolParams().getMinFeeRefScriptCostPerByte(), MutationWorld.BIG_REFERENCE_SCRIPT_SIZE);
    }

    @Test
    @Covers("LEDGER.ConwayWdrlNotDelegatedToDRep")
    void keyHashWithdrawalAccountsMustBeDelegatedToADRep() {
        // dev-cc is registered without any delegation.
        assertThat(run(withdrawing(TestKey.DEV_CC, MutationWorld.NETWORK, MutationWorld.REWARD_BALANCE), 10))
                .containsExactly("LEDGER.ConwayWdrlNotDelegatedToDRep");
        assertThat(run(withdrawing(TestKey.DEV_CC, MutationWorld.NETWORK, MutationWorld.REWARD_BALANCE), 11))
                .containsExactly("LEDGER.ConwayWdrlNotDelegatedToDRep");
        // dev-bb is delegated to AlwaysAbstain, a predefined DRep: delegated.
        assertThat(run(withdrawing(TestKey.DEV_BB, MutationWorld.NETWORK, ada(5)), 10)).containsExactly("Valid");
        // Judged on the accounts before the certificates: delegating in the same transaction does not help.
        TxSpec delegating = withdrawing(TestKey.DEV_CC, MutationWorld.NETWORK, MutationWorld.REWARD_BALANCE);
        delegating.certs.add(new VoteDelegCert(MutationWorld.stakeCredential(TestKey.DEV_CC),
                DRep.addrKeyHash(TestKey.DEV_77.keyHash())));
        assertThat(run(delegating, 10)).containsExactly("LEDGER.ConwayWdrlNotDelegatedToDRep");
    }

    @Test
    void scriptHashWithdrawalAccountsAreNotChecked() throws Exception {
        String scriptHash = HexUtil.encodeHexString(MutationWorld.NATIVE_SCRIPT.getScriptHash());
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .account(new AccountState(CredentialKey.script(scriptHash), MutationWorld.KEY_DEPOSIT, ada(3), null,
                        null))
                .build();
        TxSpec spec = MutationWorld.simpleSpec();
        spec.withdrawals.add(new Withdrawal(AddressProvider.getRewardAddress(MutationWorld.NATIVE_SCRIPT,
                MutationWorld.NETWORK).toBech32(), ada(3)));
        spec.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        spec.changeAdjust = ada(3);
        byte[] cbor = ConwayTxBuilder.build(spec, view).cbor();
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), cbor, view,
                MutationWorld.env(10), null))).containsExactly("Valid");
    }

    @Test
    void theThreePredicatesAccumulateInHaskellsOrder() {
        // Treasury value, reference-script size, withdrawal delegation: three LEDGER predicates in execution order,
        // listed in reverse.
        TxSpec spec = withdrawing(TestKey.DEV_CC, MutationWorld.NETWORK, MutationWorld.REWARD_BALANCE);
        spec.currentTreasuryValue = BigInteger.ONE;
        spec.referenceInputs.add(MutationWorld.BIG_REFERENCE_SCRIPT_INPUT);
        spec.feeAdjust = bigReferenceScriptFee();
        assertThat(run(spec, 10)).containsExactly("LEDGER.ConwayWdrlNotDelegatedToDRep",
                "LEDGER.ConwayTxRefScriptsSizeTooBig", "LEDGER.ConwayTreasuryValueMismatch");
    }

    @Test
    void theLedgerPredicatesRunOnlyForPhase2ValidTransactions() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.isValid = false;
        spec.currentTreasuryValue = BigInteger.ONE;
        assertThat(run(spec, 10)).doesNotContain("LEDGER.ConwayTreasuryValueMismatch");
    }
}
