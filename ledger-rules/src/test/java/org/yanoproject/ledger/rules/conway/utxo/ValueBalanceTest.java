package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.rules.conway.EngineTestSupport.run;

/**
 * Deposits and refunds of Conway value conservation, against the pre-certificate state (ADR-056 invariant 5):
 * {@code conwayTotalDepositsTxBody} and {@code conwayTotalRefundsTxCerts}.
 */
class ValueBalanceTest {

    private static final ConwayParams PP = new ConwayParams(MutationWorld.protocolParams());
    private static final BigInteger KEY_DEPOSIT = BigInteger.valueOf(2_000_000);
    private static final byte[] STAKE_KEY = HexUtil.decodeHexString(TestKey.DEV_42.keyHash());
    private static final byte[] OTHER_KEY = HexUtil.decodeHexString(TestKey.DEV_AA.keyHash());

    @Test
    void stakeRegistrationsPayTheKeyDepositOfTheParametersNotOfTheCertificate() {
        List<Certificate> certs = List.of(new StakeRegistration(StakeCredential.fromKeyHash(STAKE_KEY)),
                new RegCert(StakeCredential.fromKeyHash(OTHER_KEY), BigInteger.valueOf(7)));
        assertThat(ValueBalance.deposits(certs, 0, PP, MutationWorld.view()))
                .isEqualTo(KEY_DEPOSIT.multiply(BigInteger.TWO));
    }

    @Test
    void newPoolsDRepsAndProposalsPayTheirDeposits() {
        PoolRegistration fresh = PoolRegistration.builder().operator(new byte[28]).vrfKeyHash(new byte[32]).build();
        PoolRegistration known = PoolRegistration.builder().operator(OTHER_KEY).vrfKeyHash(new byte[32]).build();
        LedgerView view = MutationWorld.builder(MutationWorld.protocolParams()).pool(known, BigInteger.ONE).build();
        List<Certificate> certs = List.of(fresh, fresh, known,
                new RegDRepCert(Credential.fromKey(STAKE_KEY), BigInteger.ONE, null));
        // One new pool (the second registration of the same pool is a re-registration), no deposit for the
        // registered one, ppDRepDeposit per DRep, ppGovActionDeposit per proposal.
        assertThat(ValueBalance.deposits(certs, 2, PP, view)).isEqualTo(PP.poolDeposit().add(PP.drepDeposit())
                .add(PP.govActionDeposit().multiply(BigInteger.TWO)));
    }

    @Test
    void refundsComeFromThePreCertificateAccountOrTheSameTransactionsRegistration() {
        CredentialKey registered = CredentialKey.key(TestKey.DEV_42.keyHash());
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .account(AccountState.registered(registered, BigInteger.valueOf(3_000_000))).build();

        assertThat(ValueBalance.refunds(List.of(new StakeDeregistration(StakeCredential.fromKeyHash(STAKE_KEY))), PP,
                view)).as("the recorded deposit, not ppKeyDeposit").isEqualTo(BigInteger.valueOf(3_000_000));
        assertThat(ValueBalance.refunds(List.of(new RegCert(StakeCredential.fromKeyHash(OTHER_KEY), KEY_DEPOSIT),
                new UnregCert(StakeCredential.fromKeyHash(OTHER_KEY), BigInteger.ONE)), PP, view))
                .as("registered earlier in the same transaction").isEqualTo(KEY_DEPOSIT);
        assertThat(ValueBalance.refunds(List.of(new UnregCert(StakeCredential.fromKeyHash(OTHER_KEY), KEY_DEPOSIT)),
                PP, view)).as("not registered: nothing").isZero();
        assertThat(ValueBalance.refunds(List.of(new UnregDRepCert(Credential.fromKey(STAKE_KEY),
                BigInteger.valueOf(9))), PP, view)).as("a DRep refund is the certificate's amount")
                .isEqualTo(BigInteger.valueOf(9));
    }

    @Test
    void anUnavailableAccountFailsClosed() {
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .unavailable(InMemoryLedgerView.Area.ACCOUNTS).build();
        assertThatThrownBy(() -> ValueBalance.refunds(
                List.of(new StakeDeregistration(StakeCredential.fromKeyHash(STAKE_KEY))), PP, view))
                .isInstanceOf(LedgerStateUnavailableException.class);
    }

    @Test
    void aStakeRegistrationBalancesWithItsDeposit() {
        TxSpec paid = MutationWorld.simpleSpec();
        paid.certs.add(new RegCert(StakeCredential.fromKeyHash(OTHER_KEY), KEY_DEPOSIT));
        paid.changeAdjust = KEY_DEPOSIT.negate();
        assertThat(run(paid)).containsExactly("Valid");

        TxSpec unpaid = MutationWorld.simpleSpec();
        unpaid.certs.add(new RegCert(StakeCredential.fromKeyHash(OTHER_KEY), KEY_DEPOSIT));
        assertThat(run(unpaid)).containsExactly("UTXO.ValueNotConservedUTxO");
    }
}
