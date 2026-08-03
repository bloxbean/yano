package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoJubjubBatchProofTest {

    @Test
    void b16MaximumBatchProducesOneRealConstantSizeProof()
            throws Exception {
        var profile = EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        List<EutxoL2Transaction> transactions = new ArrayList<>();
        for (int index = 0; index < profile.maximumTransactions(); index++) {
            transactions.add(EutxoJubjubBatchCircuitTest.transaction(
                    BigInteger.valueOf(index + 1L), index));
        }
        var statement = EutxoJubjubBatchCircuit.statement(
                profile, new byte[32], transactions);
        BigInteger[] witness = EutxoJubjubBatchCircuit.witness(
                profile, statement, transactions);

        try (var setup = EutxoGroth16DevelopmentSetup.create(
                EutxoJubjubBatchCircuit.circuit(profile))) {
            var proof = setup.prove(statement.ordered(), witness);
            assertThat(setup.verify(proof)).isTrue();
            assertThat(setup.publicInputCount()).isEqualTo(8);
            assertThat(statement.batchSize()).isEqualTo(
                    BigInteger.valueOf(16));
            assertThat(proof.compressedProof()).isNotNull();
            System.out.printf(
                    "D3_B16 constraints=%d wires=%d setupMillis=%d proofMillis=%d proofBytes=192%n",
                    setup.constraintCount(),
                    setup.wireCount(),
                    setup.setupMillis(),
                    proof.proofMillis());
        }
    }
}
