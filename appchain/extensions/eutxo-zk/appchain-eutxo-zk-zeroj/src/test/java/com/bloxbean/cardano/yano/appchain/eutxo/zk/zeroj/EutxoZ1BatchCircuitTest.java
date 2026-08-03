package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoZ1BatchCircuitTest {

    @Test
    void hostOracleAndCircuitWitnessAgreeAcrossBoundedCorpus() {
        Random random = new Random(20260724L);
        for (int vector = 0; vector < 64; vector++) {
            int size = 1 + random.nextInt(
                    EutxoKeyPaymentBatchCircuit.MAX_BATCH);
            List<EutxoKeyPaymentBatch.Payment> payments = new ArrayList<>();
            for (int index = 0; index < size; index++) {
                long input = 1 + random.nextLong(1_000_000);
                long first = random.nextLong(input + 1);
                payments.add(new EutxoKeyPaymentBatch.Payment(
                        BigInteger.valueOf(input),
                        BigInteger.valueOf(first),
                        BigInteger.valueOf(input - first)));
            }
            EutxoKeyPaymentBatch batch = new EutxoKeyPaymentBatch(
                    payments, BigInteger.valueOf(1000L + vector));
            byte[] previousRoot = new byte[32];
            new Random(vector).nextBytes(previousRoot);

            EutxoZkPublicInputs inputs =
                    EutxoKeyPaymentBatchCircuit.publicInputs(
                            previousRoot, batch);
            BigInteger expectedRoot = ZerojScalars.scalar(previousRoot);
            BigInteger expectedDigest =
                    EutxoKeyPaymentBatchCircuit.BATCH_DOMAIN;
            for (EutxoKeyPaymentBatch.Payment payment : payments) {
                BigInteger transition =
                        EutxoKeyPaymentBatchCircuit.transitionDigest(payment);
                expectedRoot = poseidon(expectedRoot, transition);
                expectedDigest = poseidon(expectedDigest, transition);
            }
            assertThat(inputs.nextRoot()).isEqualTo(expectedRoot);
            assertThat(inputs.transitionDigest()).isEqualTo(expectedDigest);
            assertThat(inputs.batchSize())
                    .isEqualTo(BigInteger.valueOf(size));
            assertThat(EutxoKeyPaymentBatchCircuit.witness(inputs, batch))
                    .hasSizeGreaterThan(100);
        }
    }

    @Test
    void realFourPaymentProofVerifiesAndPublicTamperingFails() {
        EutxoKeyPaymentBatch batch = new EutxoKeyPaymentBatch(
                List.of(
                        payment(100, 70),
                        payment(70, 31),
                        payment(31, 20),
                        payment(20, 1)),
                BigInteger.valueOf(424242));
        EutxoZkPublicInputs inputs =
                EutxoKeyPaymentBatchCircuit.publicInputs(new byte[32], batch);

        try (var setup = EutxoBatchGroth16DevelopmentSetup.create()) {
            var proof = setup.prove(inputs, batch);
            assertThat(setup.verify(proof)).isTrue();
            assertThat(setup.constraintCount()).isGreaterThan(8_000);
            assertThat(proof.compressedProof().piA()).hasSize(48);

            EutxoZkPublicInputs tampered = new EutxoZkPublicInputs(
                    inputs.previousRoot(),
                    inputs.nextRoot(),
                    inputs.transitionDigest().add(BigInteger.ONE),
                    inputs.ownerCommitment(),
                    inputs.batchSize());
            assertThat(setup.verify(
                    new EutxoGroth16DevelopmentSetup.ProofArtifact(
                            tampered,
                            proof.proof(),
                            proof.compressedProof(),
                            proof.proofMillis())))
                    .isFalse();

            // Declared witness order is constant/publics, ownerSecret, then
            // enabled/input/first/second for each slot. Mutating the first
            // second-output breaks conservation and must not verify.
            BigInteger[] inflated =
                    EutxoKeyPaymentBatchCircuit.witness(inputs, batch);
            inflated[10] = inflated[10].add(BigInteger.ONE);
            assertThat(setup.verify(
                    setup.proveUnchecked(inputs, inflated))).isFalse();
            System.out.printf(
                    "Z1_BATCH constraints=%d wires=%d proofMillis=%d%n",
                    setup.constraintCount(),
                    setup.wireCount(),
                    proof.proofMillis());
        }
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

    private static BigInteger poseidon(BigInteger left, BigInteger right) {
        return PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, left, right);
    }
}
