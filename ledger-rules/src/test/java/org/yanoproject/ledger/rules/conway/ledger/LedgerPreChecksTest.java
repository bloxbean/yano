package org.yanoproject.ledger.rules.conway.ledger;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
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
        assertThat(run(withdrawing(TestKey.DEV_AA, MutationWorld.NETWORK, BigInteger.ZERO), 11))
                .containsExactly("LEDGER.ConwayWithdrawalsMissingAccounts");
        // Another network counts as missing (categorizeWithdrawals); UTXO reports the network too, first.
        assertThat(run(withdrawing(TestKey.DEV_BB, Networks.mainnet(), ada(5)), 11))
                .containsExactly("UTXO.WrongNetworkWithdrawal", "LEDGER.ConwayWithdrawalsMissingAccounts");
        // Before 11 the same fault is CERTS' WithdrawalsNotInRewardsCERTS.
        assertThat(run(withdrawing(TestKey.DEV_AA, MutationWorld.NETWORK, BigInteger.ZERO), 10))
                .containsExactly("CERTS.WithdrawalsNotInRewardsCERTS");
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
                "LEDGER.ConwayWithdrawalsMissingAccounts");
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
                "LEDGER.ConwayWithdrawalsMissingAccounts");
    }
}
