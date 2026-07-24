package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.circuit.CircuitBuilder;
import com.bloxbean.cardano.zeroj.circuit.Variable;
import com.bloxbean.cardano.zeroj.circuit.lib.Poseidon;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Z1 fixed-shape circuit for one to four key-authorized value-conserving payments. */
public final class EutxoKeyPaymentBatchCircuit {
    public static final int MAX_BATCH =
            EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.maximumBatchSize();
    public static final BigInteger BATCH_DOMAIN =
            ZerojScalars.domain("yano:eutxo:key-payment-batch:v1");

    private EutxoKeyPaymentBatchCircuit() {
    }

    public static CircuitBuilder circuit() {
        return circuit(EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS, false);
    }

    static CircuitBuilder settlementCircuit() {
        return circuit(EutxoZkProfile.Z3_VALIDITY_SETTLEMENT, true);
    }

    private static CircuitBuilder circuit(
            EutxoZkProfile profile,
            boolean settlementBound
    ) {
        CircuitBuilder builder = CircuitBuilder.create(profile.circuitId())
                .publicVar("previousRoot")
                .publicVar("nextRoot")
                .publicVar("transitionDigest")
                .publicVar("ownerCommitment")
                .publicVar("batchSize");
        if (settlementBound) {
            builder.publicVar("settlementContext")
                    .publicVar("batchDataCommitment")
                    .publicVar("withdrawalCommitment")
                    .secretVar("settlementContextWitness")
                    .secretVar("batchDataCommitmentWitness")
                    .secretVar("withdrawalCommitmentWitness");
        }
        builder.secretVar("ownerSecret");
        for (int index = 0; index < MAX_BATCH; index++) {
            builder.secretVar("enabled" + index)
                    .secretVar("input" + index)
                    .secretVar("firstOutput" + index)
                    .secretVar("secondOutput" + index);
        }
        return builder.define(api -> {
            Variable owner = Poseidon.hash(
                    api,
                    PoseidonParamsBLS12_381T3.INSTANCE,
                    api.var("ownerSecret"),
                    api.constant(EutxoKeyPaymentCircuit.OWNER_DOMAIN));
            api.assertEqual(api.var("ownerCommitment"), owner);
            api.assertInRange(api.var("ownerSecret"), 252);

            Variable runningRoot = api.var("previousRoot");
            Variable runningBatch = api.constant(BATCH_DOMAIN);
            Variable enabledSum = api.constant(0);
            Variable previousEnabled = api.constant(1);
            for (int index = 0; index < MAX_BATCH; index++) {
                Variable enabled = api.var("enabled" + index);
                Variable input = api.var("input" + index);
                Variable first = api.var("firstOutput" + index);
                Variable second = api.var("secondOutput" + index);
                api.assertBoolean(enabled);
                api.assertInRange(input, 64);
                api.assertInRange(first, 64);
                api.assertInRange(second, 64);

                Variable disabled = api.not(enabled);
                api.assertEqual(api.mul(disabled, input), api.constant(0));
                api.assertEqual(api.mul(disabled, first), api.constant(0));
                api.assertEqual(api.mul(disabled, second), api.constant(0));
                api.assertEqual(
                        api.mul(enabled, input),
                        api.mul(enabled, api.add(first, second)));
                api.assertEqual(
                        api.mul(enabled, api.isZero(input)),
                        api.constant(0));
                api.assertEqual(
                        api.mul(enabled, api.not(previousEnabled)),
                        api.constant(0));

                Variable outputDigest = Poseidon.hash(
                        api,
                        PoseidonParamsBLS12_381T3.INSTANCE,
                        input,
                        first);
                Variable transitionDigest = Poseidon.hash(
                        api,
                        PoseidonParamsBLS12_381T3.INSTANCE,
                        outputDigest,
                        second);
                Variable candidateRoot = Poseidon.hash(
                        api,
                        PoseidonParamsBLS12_381T3.INSTANCE,
                        runningRoot,
                        transitionDigest);
                Variable candidateBatch = Poseidon.hash(
                        api,
                        PoseidonParamsBLS12_381T3.INSTANCE,
                        runningBatch,
                        transitionDigest);
                runningRoot = api.select(enabled, candidateRoot, runningRoot);
                runningBatch = api.select(enabled, candidateBatch, runningBatch);
                enabledSum = api.add(enabledSum, enabled);
                previousEnabled = enabled;
            }
            api.assertEqual(api.var("nextRoot"), runningRoot);
            api.assertEqual(api.var("transitionDigest"), runningBatch);
            api.assertEqual(api.var("batchSize"), enabledSum);
            if (settlementBound) {
                api.assertEqual(
                        api.var("settlementContext"),
                        api.var("settlementContextWitness"));
                api.assertEqual(
                        api.var("batchDataCommitment"),
                        api.var("batchDataCommitmentWitness"));
                api.assertEqual(
                        api.var("withdrawalCommitment"),
                        api.var("withdrawalCommitmentWitness"));
            }
        });
    }

    public static EutxoZkPublicInputs publicInputs(
            byte[] previousRoot,
            EutxoKeyPaymentBatch batch
    ) {
        BigInteger root = ZerojScalars.scalar(previousRoot);
        BigInteger digest = BATCH_DOMAIN;
        for (EutxoKeyPaymentBatch.Payment payment : batch.payments()) {
            BigInteger transition = transitionDigest(payment);
            root = poseidon(root, transition);
            digest = poseidon(digest, transition);
        }
        BigInteger owner = poseidon(
                batch.ownerSecret(), EutxoKeyPaymentCircuit.OWNER_DOMAIN);
        return new EutxoZkPublicInputs(
                ZerojScalars.scalar(previousRoot),
                root,
                digest,
                owner,
                BigInteger.valueOf(batch.payments().size()));
    }

    static BigInteger[] witness(
            EutxoZkPublicInputs inputs,
            EutxoKeyPaymentBatch batch
    ) {
        return witness(circuit(), inputs, null, batch);
    }

    static BigInteger[] witness(
            EutxoZkSettlementPublicInputs inputs,
            EutxoKeyPaymentBatch batch
    ) {
        return witness(
                settlementCircuit(), inputs.batchInputs(), inputs, batch);
    }

    private static BigInteger[] witness(
            CircuitBuilder circuit,
            EutxoZkPublicInputs inputs,
            EutxoZkSettlementPublicInputs settlementInputs,
            EutxoKeyPaymentBatch batch
    ) {
        Map<String, List<BigInteger>> assignments = new LinkedHashMap<>();
        assignments.put("previousRoot", List.of(inputs.previousRoot()));
        assignments.put("nextRoot", List.of(inputs.nextRoot()));
        assignments.put("transitionDigest", List.of(inputs.transitionDigest()));
        assignments.put("ownerCommitment", List.of(inputs.ownerCommitment()));
        assignments.put("batchSize", List.of(inputs.batchSize()));
        if (settlementInputs != null) {
            assignments.put("settlementContext",
                    List.of(settlementInputs.settlementContext()));
            assignments.put("batchDataCommitment",
                    List.of(settlementInputs.batchDataCommitment()));
            assignments.put("withdrawalCommitment",
                    List.of(settlementInputs.withdrawalCommitment()));
            assignments.put("settlementContextWitness",
                    List.of(settlementInputs.settlementContext()));
            assignments.put("batchDataCommitmentWitness",
                    List.of(settlementInputs.batchDataCommitment()));
            assignments.put("withdrawalCommitmentWitness",
                    List.of(settlementInputs.withdrawalCommitment()));
        }
        assignments.put("ownerSecret", List.of(batch.ownerSecret()));
        for (int index = 0; index < MAX_BATCH; index++) {
            boolean enabled = index < batch.payments().size();
            EutxoKeyPaymentBatch.Payment payment = enabled
                    ? batch.payments().get(index)
                    : new EutxoKeyPaymentBatch.Payment(
                    BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
            assignments.put("enabled" + index,
                    List.of(enabled ? BigInteger.ONE : BigInteger.ZERO));
            assignments.put("input" + index,
                    List.of(enabled ? payment.inputLovelace() : BigInteger.ZERO));
            assignments.put("firstOutput" + index,
                    List.of(enabled ? payment.firstOutputLovelace() : BigInteger.ZERO));
            assignments.put("secondOutput" + index,
                    List.of(enabled ? payment.secondOutputLovelace() : BigInteger.ZERO));
        }
        return circuit.calculateWitness(assignments, CurveId.BLS12_381);
    }

    static BigInteger transitionDigest(EutxoKeyPaymentBatch.Payment payment) {
        return poseidon(
                poseidon(payment.inputLovelace(), payment.firstOutputLovelace()),
                payment.secondOutputLovelace());
    }

    private static BigInteger poseidon(BigInteger left, BigInteger right) {
        return PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, left, right);
    }
}
