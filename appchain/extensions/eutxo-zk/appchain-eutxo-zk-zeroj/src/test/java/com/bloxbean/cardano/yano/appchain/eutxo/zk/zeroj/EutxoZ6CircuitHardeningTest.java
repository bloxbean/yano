package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.zeroj.api.CurveId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoZ6CircuitHardeningTest {

    @Test
    void deterministicFuzzCorpusSatisfiesCircuitAndMutationsNeverAlias() {
        var r1cs = EutxoKeyPaymentSettlementCircuit.circuit()
                .compileR1CS(CurveId.BLS12_381);
        Random random = new Random(0x5a365f7574786fL);
        for (int vector = 0; vector < 128; vector++) {
            int count = 1 + random.nextInt(4);
            List<EutxoKeyPaymentBatch.Payment> payments =
                    new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                long input = 1 + random.nextLong(1_000_000);
                long first = random.nextLong(input + 1);
                payments.add(new EutxoKeyPaymentBatch.Payment(
                        BigInteger.valueOf(input),
                        BigInteger.valueOf(first),
                        BigInteger.valueOf(input - first)));
            }
            var batch = new EutxoKeyPaymentBatch(
                    payments, BigInteger.valueOf(100_000L + vector));
            var data = new EutxoZkBatchData(payments);
            byte[] previousRoot = new byte[32];
            random.nextBytes(previousRoot);
            var inputs = EutxoKeyPaymentSettlementCircuit.publicInputs(
                    "fuzz-chain", 9, "ab".repeat(32), previousRoot,
                    batch, data.commitment(),
                    EutxoKeyPaymentSettlementCircuit
                            .withdrawalCommitment(batch));
            assertThat(satisfied(
                    r1cs.constraints(),
                    EutxoKeyPaymentSettlementCircuit.witness(inputs, batch),
                    r1cs.prime())).isTrue();
            assertThat(EutxoZkBatchData.decode(data.canonicalBytes()))
                    .isEqualTo(data);

            byte[] mutated = data.canonicalBytes();
            int offset = random.nextInt(mutated.length);
            mutated[offset] ^= (byte) (1 << random.nextInt(8));
            try {
                EutxoZkBatchData decoded =
                        EutxoZkBatchData.decode(mutated);
                assertThat(decoded.commitment())
                        .isNotEqualTo(data.commitment());
            } catch (IllegalArgumentException expectedRejection) {
                assertThat(expectedRejection).isNotNull();
            }
        }
    }

    @Test
    void aProductionManifestCannotDescribeSinglePartyDevelopmentSetup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new EutxoCeremonyManifest(
                        "invalid-production",
                        EutxoCeremonyManifest.Kind.PRODUCTION,
                        "zeroj-single-development-setup",
                        1,
                        "11".repeat(32),
                        "22".repeat(32),
                        "circuit",
                        "33".repeat(32),
                        Map.of("pk.bin", "44".repeat(32))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-party");
    }

    private static boolean satisfied(
            List<com.bloxbean.cardano.zeroj.api.R1CSConstraint> constraints,
            BigInteger[] witness,
            BigInteger field
    ) {
        for (var constraint : constraints) {
            BigInteger left = evaluate(constraint.a(), witness, field);
            BigInteger right = evaluate(constraint.b(), witness, field);
            BigInteger result = evaluate(constraint.c(), witness, field);
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
                    term.getValue().multiply(witness[term.getKey()]));
        }
        return result.mod(field);
    }
}
