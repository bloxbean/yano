package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.circuit.CircuitBuilder;
import com.bloxbean.cardano.zeroj.circuit.lib.Poseidon;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Z0 bounded one-transition circuit.
 *
 * <p>It proves that the next validity root commits to the previous root and
 * transition digest, and that the prover knows the owner-key preimage bound
 * to the public owner commitment. Z1 expands this feasibility circuit into a
 * bounded EUTxO batch.</p>
 */
public final class EutxoKeyPaymentCircuit {
    public static final BigInteger OWNER_DOMAIN =
            ZerojScalars.domain("yano:eutxo:key-owner:v1");

    private EutxoKeyPaymentCircuit() {
    }

    public static CircuitBuilder circuit() {
        return CircuitBuilder.create(EutxoZkProfile.Z0_SINGLE_KEY_PAYMENT.circuitId())
                .publicVar("previousRoot")
                .publicVar("nextRoot")
                .publicVar("transitionDigest")
                .publicVar("ownerCommitment")
                .publicVar("batchSize")
                .secretVar("ownerSecret")
                .define(api -> {
                    var expectedRoot = Poseidon.hash(
                            api,
                            PoseidonParamsBLS12_381T3.INSTANCE,
                            api.var("previousRoot"),
                            api.var("transitionDigest"));
                    var expectedOwner = Poseidon.hash(
                            api,
                            PoseidonParamsBLS12_381T3.INSTANCE,
                            api.var("ownerSecret"),
                            api.constant(OWNER_DOMAIN));
                    api.assertEqual(api.var("nextRoot"), expectedRoot);
                    api.assertEqual(api.var("ownerCommitment"), expectedOwner);
                    api.assertEqual(api.var("batchSize"), api.constant(1));
                    api.assertInRange(api.var("ownerSecret"), 252);
                });
    }

    public static EutxoZkPublicInputs publicInputs(
            byte[] previousRoot,
            byte[] transitionDigest,
            BigInteger ownerSecret
    ) {
        Objects.requireNonNull(ownerSecret, "ownerSecret");
        if (ownerSecret.signum() <= 0 || ownerSecret.bitLength() > 252) {
            throw new IllegalArgumentException(
                    "owner secret must be a positive 252-bit scalar");
        }
        BigInteger previous = ZerojScalars.scalar(previousRoot);
        BigInteger transition = ZerojScalars.scalar(transitionDigest);
        BigInteger next = PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, previous, transition);
        BigInteger owner = PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, ownerSecret, OWNER_DOMAIN);
        return new EutxoZkPublicInputs(
                previous, next, transition, owner, BigInteger.ONE);
    }

    static BigInteger[] witness(
            EutxoZkPublicInputs inputs,
            BigInteger ownerSecret
    ) {
        return circuit().calculateWitness(Map.of(
                "previousRoot", List.of(inputs.previousRoot()),
                "nextRoot", List.of(inputs.nextRoot()),
                "transitionDigest", List.of(inputs.transitionDigest()),
                "ownerCommitment", List.of(inputs.ownerCommitment()),
                "batchSize", List.of(inputs.batchSize()),
                "ownerSecret", List.of(ownerSecret)), CurveId.BLS12_381);
    }
}
