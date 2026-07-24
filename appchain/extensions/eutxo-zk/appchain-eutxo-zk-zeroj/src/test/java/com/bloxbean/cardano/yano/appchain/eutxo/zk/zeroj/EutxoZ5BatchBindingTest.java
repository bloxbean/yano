package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.zeroj.api.CurveId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZ5BatchBindingTest {

    @Test
    void circuitDerivesCanonicalBatchCommitmentFromWitness() {
        var batch = new EutxoKeyPaymentBatch(
                List.of(
                        payment(100, 60),
                        payment(60, 25)),
                BigInteger.valueOf(424242));
        var data = new EutxoZkBatchData(batch.payments());
        var inputs = EutxoKeyPaymentSettlementCircuit.publicInputs(
                "payments", 0, "11".repeat(32),
                new byte[32], batch, data.commitment(),
                EutxoKeyPaymentSettlementCircuit
                        .withdrawalCommitment(batch));
        var circuit = EutxoKeyPaymentSettlementCircuit.circuit();
        var constraints = circuit.compileR1CS(CurveId.BLS12_381);

        assertThat(satisfied(
                constraints.constraints(),
                EutxoKeyPaymentSettlementCircuit.witness(
                        inputs, batch),
                constraints.prime())).isTrue();

        var forged = new EutxoZkSettlementPublicInputs(
                inputs.previousRoot(),
                inputs.nextRoot(),
                inputs.transitionDigest(),
                inputs.ownerCommitment(),
                inputs.batchSize(),
                inputs.settlementContext(),
                inputs.batchDataCommitment().add(BigInteger.ONE),
                inputs.withdrawalCommitment());
        assertThatThrownBy(() ->
                EutxoKeyPaymentSettlementCircuit.witness(
                        forged, batch))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("batchDataCommitment");
    }

    private static boolean satisfied(
            List<com.bloxbean.cardano.zeroj.api.R1CSConstraint>
                    constraints,
            BigInteger[] witness,
            BigInteger field
    ) {
        for (var constraint : constraints) {
            BigInteger left = evaluate(
                    constraint.a(), witness, field);
            BigInteger right = evaluate(
                    constraint.b(), witness, field);
            BigInteger result = evaluate(
                    constraint.c(), witness, field);
            if (!left.multiply(right).mod(field).equals(result)) {
                return false;
            }
        }
        return true;
    }

    private static BigInteger evaluate(
            Map<Integer, BigInteger> terms,
            BigInteger[] witness,
            BigInteger field
    ) {
        BigInteger result = BigInteger.ZERO;
        for (var term : terms.entrySet()) {
            result = result.add(
                    term.getValue().multiply(
                            witness[term.getKey()]));
        }
        return result.mod(field);
    }

    private static EutxoKeyPaymentBatch.Payment payment(
            long input,
            long first
    ) {
        return new EutxoKeyPaymentBatch.Payment(
                BigInteger.valueOf(input),
                BigInteger.valueOf(first),
                BigInteger.valueOf(input - first));
    }
}
