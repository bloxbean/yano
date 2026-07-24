package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.circuit.CircuitAPI;
import com.bloxbean.cardano.zeroj.circuit.CircuitBuilder;
import com.bloxbean.cardano.zeroj.circuit.Variable;
import com.bloxbean.cardano.zeroj.circuit.lib.Poseidon;
import com.bloxbean.cardano.zeroj.circuit.lib.hash.Blake2b;
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
            Variable runningWithdrawal = api.constant(0);
            Variable enabledSum = api.constant(0);
            Variable previousEnabled = api.constant(1);
            Variable[] inputValues = new Variable[MAX_BATCH];
            Variable[] firstValues = new Variable[MAX_BATCH];
            Variable[] secondValues = new Variable[MAX_BATCH];
            for (int index = 0; index < MAX_BATCH; index++) {
                Variable enabled = api.var("enabled" + index);
                Variable input = api.var("input" + index);
                Variable first = api.var("firstOutput" + index);
                Variable second = api.var("secondOutput" + index);
                inputValues[index] = input;
                firstValues[index] = first;
                secondValues[index] = second;
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
                runningWithdrawal = api.add(
                        runningWithdrawal,
                        api.mul(enabled, second));
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
                        batchDataCommitment(
                                api, enabledSum,
                                inputValues, firstValues, secondValues));
                api.assertEqual(
                        api.var("withdrawalCommitment"),
                        api.var("withdrawalCommitmentWitness"));
                api.assertEqual(
                        api.var("withdrawalCommitment"),
                        runningWithdrawal);
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

    private static Variable batchDataCommitment(
            CircuitAPI api,
            Variable count,
            Variable[] inputs,
            Variable[] firstOutputs,
            Variable[] secondOutputs
    ) {
        Variable[] message = new Variable[4 + 1 + (MAX_BATCH * 3 * 8)];
        int cursor = 0;
        message[cursor++] = api.constant(0);
        message[cursor++] = api.constant(0);
        message[cursor++] = api.constant(0);
        message[cursor++] = api.constant(2);
        message[cursor++] = count;
        for (int index = 0; index < MAX_BATCH; index++) {
            cursor = append(
                    message, cursor,
                    bigEndian64(api, inputs[index]));
            cursor = append(
                    message, cursor,
                    bigEndian64(api, firstOutputs[index]));
            cursor = append(
                    message, cursor,
                    bigEndian64(api, secondOutputs[index]));
        }
        Variable[] digest = Blake2b.hash256(api, message);
        Variable[] scalarBits = new Variable[256];
        for (int index = 0; index < digest.length; index++) {
            Variable[] byteBits = api.toBinary(digest[index], 8);
            int bitOffset = (digest.length - 1 - index) * 8;
            System.arraycopy(
                    byteBits, 0, scalarBits, bitOffset, 8);
        }
        return api.fromBinary(scalarBits);
    }

    private static Variable[] bigEndian64(
            CircuitAPI api,
            Variable value
    ) {
        Variable[] bits = api.toBinary(value, 64);
        Variable[] bytes = new Variable[8];
        for (int byteIndex = 0; byteIndex < bytes.length; byteIndex++) {
            Variable[] byteBits = new Variable[8];
            int bitOffset = (bytes.length - 1 - byteIndex) * 8;
            System.arraycopy(
                    bits, bitOffset, byteBits, 0, 8);
            bytes[byteIndex] = api.fromBinary(byteBits);
        }
        return bytes;
    }

    private static int append(
            Variable[] target,
            int cursor,
            Variable[] source
    ) {
        System.arraycopy(source, 0, target, cursor, source.length);
        return cursor + source.length;
    }

    private static BigInteger poseidon(BigInteger left, BigInteger right) {
        return PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, left, right);
    }
}
