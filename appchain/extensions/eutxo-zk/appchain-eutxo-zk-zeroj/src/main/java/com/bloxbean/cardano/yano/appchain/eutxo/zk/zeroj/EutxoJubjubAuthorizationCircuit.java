package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.circuit.CircuitBuilder;
import com.bloxbean.cardano.zeroj.circuit.lib.Poseidon;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.InCircuitEdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.InCircuitJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubPoint;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Experimental trusted-prover circuit for one L2 Jubjub authorization.
 *
 * <p>The signature equation and scalar bounds are constrained. Point decoding,
 * curve and subgroup validation remain host guards in
 * {@code zeroj-jubjub-dev-v1}; a later hardened profile must add those
 * constraints and use new circuit/key/validator identities.</p>
 */
public final class EutxoJubjubAuthorizationCircuit {
    public static final String CIRCUIT_ID =
            "eutxo-jubjub-authorization-dev-v1";

    private EutxoJubjubAuthorizationCircuit() {
    }

    public static CircuitBuilder circuit() {
        return CircuitBuilder.create(CIRCUIT_ID)
                .publicVar("messageCommitment")
                .publicVar("publicKeyCommitment")
                .secretVar("publicKeyU")
                .secretVar("publicKeyV")
                .secretVar("rU")
                .secretVar("rV")
                .secretVar("s")
                .secretVar("kModL")
                .secretVar("kQuotient")
                .define(api -> {
                    var publicKey = new InCircuitJubjub.Point(
                            api.var("publicKeyU"),
                            api.var("publicKeyV"),
                            api.constant(1),
                            api.mul(api.var("publicKeyU"),
                                    api.var("publicKeyV")));
                    var rPoint = new InCircuitJubjub.Point(
                            api.var("rU"),
                            api.var("rV"),
                            api.constant(1),
                            api.mul(api.var("rU"), api.var("rV")));
                    api.assertEqual(
                            api.var("publicKeyCommitment"),
                            Poseidon.hash(
                                    api,
                                    PoseidonParamsBLS12_381T3.INSTANCE,
                                    api.var("publicKeyU"),
                                    api.var("publicKeyV")));
                    assertNotIdentity(api, publicKey);
                    assertNotIdentity(api, rPoint);
                    InCircuitEdDSAJubjub.verify(
                            api,
                            publicKey,
                            api.var("messageCommitment"),
                            rPoint,
                            api.var("s"),
                            api.var("kModL"),
                            api.var("kQuotient"));
                });
    }

    public static Statement statement(EutxoL2Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.authorizations().size() != 1) {
            throw new IllegalArgumentException(
                    "D1 authorization circuit requires exactly one signer");
        }
        EutxoL2Authorization authorization =
                transaction.authorizations().getFirst();
        JubjubPoint publicKey = canonicalPoint(authorization.publicKey());
        return new Statement(
                ZerojScalars.scalar(transaction.signingCommitment()),
                PoseidonHash.hash(
                        PoseidonParamsBLS12_381T3.INSTANCE,
                        publicKey.affineU(), publicKey.affineV()));
    }

    public static BigInteger[] witness(EutxoL2Transaction transaction) {
        Statement statement = statement(transaction);
        EutxoL2Authorization authorization =
                transaction.authorizations().getFirst();
        JubjubPoint publicKey = canonicalPoint(authorization.publicKey());
        JubjubPoint rPoint = canonicalPoint(authorization.rPoint());
        if (publicKey.isIdentity() || rPoint.isIdentity()
                || !publicKey.isInSubgroup() || !rPoint.isInSubgroup()) {
            throw new IllegalArgumentException(
                    "development witness requires non-identity subgroup points");
        }
        BigInteger s = fromLittleEndian(authorization.s());
        if (s.compareTo(JubjubCurve.SUBGROUP_ORDER) >= 0) {
            throw new IllegalArgumentException(
                    "Jubjub signature scalar is not canonical");
        }
        var reduction = InCircuitEdDSAJubjub.witnessComputeKReduction(
                rPoint, publicKey, statement.messageCommitment());
        Map<String, List<BigInteger>> assignments = new LinkedHashMap<>();
        assignments.put("messageCommitment",
                List.of(statement.messageCommitment()));
        assignments.put("publicKeyCommitment",
                List.of(statement.publicKeyCommitment()));
        assignments.put("publicKeyU", List.of(publicKey.affineU()));
        assignments.put("publicKeyV", List.of(publicKey.affineV()));
        assignments.put("rU", List.of(rPoint.affineU()));
        assignments.put("rV", List.of(rPoint.affineV()));
        assignments.put("s", List.of(s));
        assignments.put("kModL", List.of(reduction.kModL()));
        assignments.put("kQuotient", List.of(reduction.kQuotient()));
        return circuit().calculateWitness(assignments, CurveId.BLS12_381);
    }

    private static void assertNotIdentity(
            com.bloxbean.cardano.zeroj.circuit.CircuitAPI api,
            InCircuitJubjub.Point point
    ) {
        var identity = api.mul(
                api.isZero(point.u()),
                api.isZero(api.sub(point.v(), api.constant(1))));
        api.assertEqual(identity, api.constant(0));
    }

    private static JubjubPoint canonicalPoint(byte[] bytes) {
        JubjubPoint point = JubjubPoint.fromBytes(bytes);
        if (!Arrays.equals(bytes, point.toBytes())) {
            throw new IllegalArgumentException(
                    "Jubjub point is not canonically encoded");
        }
        return point;
    }

    private static BigInteger fromLittleEndian(byte[] encoded) {
        byte[] reversed = encoded.clone();
        for (int left = 0, right = reversed.length - 1;
             left < right; left++, right--) {
            byte value = reversed[left];
            reversed[left] = reversed[right];
            reversed[right] = value;
        }
        return new BigInteger(1, reversed);
    }

    public record Statement(
            BigInteger messageCommitment,
            BigInteger publicKeyCommitment
    ) {
        public Statement {
            Objects.requireNonNull(messageCommitment, "messageCommitment");
            Objects.requireNonNull(publicKeyCommitment, "publicKeyCommitment");
        }

        public List<BigInteger> ordered() {
            return List.of(messageCommitment, publicKeyCommitment);
        }
    }
}
