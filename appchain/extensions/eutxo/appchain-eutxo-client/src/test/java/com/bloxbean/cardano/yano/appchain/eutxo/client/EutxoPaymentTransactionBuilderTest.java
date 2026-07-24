package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoPaymentTransactionBuilderTest {
    @Test
    void buildsCanonicalSignedZeroFeeCardanoPayment() throws Exception {
        byte[] firstSeed = new byte[32];
        byte[] secondSeed = new byte[32];
        Arrays.fill(firstSeed, (byte) 0x31);
        Arrays.fill(secondSeed, (byte) 0x32);
        EutxoKeyWallet first = EutxoKeyWallet.fromSeed(firstSeed);
        EutxoKeyWallet second = EutxoKeyWallet.fromSeed(secondSeed);

        Transaction transaction = EutxoPaymentTransactionBuilder.signedPayment(
                new EutxoOutpoint("ab".repeat(32), 0), first,
                List.of(
                        new EutxoPaymentTransactionBuilder.Payment(
                                second.address(), BigInteger.valueOf(25_000_000)),
                        new EutxoPaymentTransactionBuilder.Payment(
                                first.address(), BigInteger.valueOf(75_000_000))),
                0, 10_000_000);

        assertThat(transaction.getBody().getFee()).isEqualTo(BigInteger.ZERO);
        assertThat(transaction.getBody().getInputs()).hasSize(1);
        assertThat(transaction.getBody().getOutputs()).hasSize(2);
        assertThat(transaction.getWitnessSet().getVkeyWitnesses()).hasSize(1);
        assertThat(Transaction.deserialize(transaction.serialize())).isNotNull();
    }

    @Test
    void rejectsInvalidBoundsBeforeBuilding() {
        byte[] seed = new byte[32];
        EutxoKeyWallet wallet = EutxoKeyWallet.fromSeed(seed);
        assertThatThrownBy(() -> EutxoPaymentTransactionBuilder.signedPayment(
                new EutxoOutpoint("ab".repeat(32), 0), wallet, List.of(), 0, 1))
                .hasMessageContaining("outputs");
        assertThatThrownBy(() -> EutxoPaymentTransactionBuilder.signedPayment(
                new EutxoOutpoint("ab".repeat(32), 0), wallet,
                List.of(new EutxoPaymentTransactionBuilder.Payment(
                        wallet.address(), BigInteger.ONE)), 2, 1))
                .hasMessageContaining("validity");
    }

    @Test
    void virtualGenesisOutpointIsDeterministicAndValueBound() {
        byte[] seed = new byte[32];
        EutxoKeyWallet wallet = EutxoKeyWallet.fromSeed(seed);
        EutxoOutpoint first = EutxoGenesisOutpoint.singleOutput(
                wallet.address(), BigInteger.valueOf(100_000_000));
        EutxoOutpoint same = EutxoGenesisOutpoint.singleOutput(
                wallet.address(), BigInteger.valueOf(100_000_000));
        EutxoOutpoint changed = EutxoGenesisOutpoint.singleOutput(
                wallet.address(), BigInteger.valueOf(99_000_000));

        assertThat(first).isEqualTo(same);
        assertThat(first).isNotEqualTo(changed);
        assertThat(first.index()).isZero();
    }
}
