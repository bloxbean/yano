package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.api.R1CSConstraint;
import com.bloxbean.cardano.zeroj.bls12381.ec.G1Point;
import com.bloxbean.cardano.zeroj.bls12381.ec.G2Point;
import com.bloxbean.cardano.zeroj.bls12381.field.Fp;
import com.bloxbean.cardano.zeroj.bls12381.field.Fp2;
import com.bloxbean.cardano.zeroj.bls12381.pairing.BLS12381Pairing;
import com.bloxbean.cardano.zeroj.crypto.groth16.Groth16Keys;
import com.bloxbean.cardano.zeroj.crypto.groth16.Groth16ProofBLS381;
import com.bloxbean.cardano.zeroj.crypto.setup.PowersOfTauBLS381;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.codec.ProverToCardano;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.codec.SnarkjsToCardano;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Single-participant Groth16 setup used only by tests and feasibility tooling.
 *
 * <p>Construction is guarded by ZeroJ's
 * {@code zeroj.allowInsecureTrustedSetup} policy. Production code must load
 * an externally generated ceremony bundle instead.</p>
 */
public final class EutxoGroth16DevelopmentSetup implements AutoCloseable {
    private final com.bloxbean.cardano.zeroj.circuit.r1cs.R1CSConstraintSystem r1cs;
    private final List<R1CSConstraint> constraints;
    private final Groth16Keys keys;
    private final long setupMillis;

    private EutxoGroth16DevelopmentSetup(
            com.bloxbean.cardano.zeroj.circuit.r1cs.R1CSConstraintSystem r1cs,
            List<R1CSConstraint> constraints,
            Groth16Keys keys,
            long setupMillis
    ) {
        this.r1cs = r1cs;
        this.constraints = constraints;
        this.keys = keys;
        this.setupMillis = setupMillis;
    }

    public static EutxoGroth16DevelopmentSetup create() {
        return create(EutxoKeyPaymentCircuit.circuit());
    }

    static EutxoGroth16DevelopmentSetup create(
            com.bloxbean.cardano.zeroj.circuit.CircuitBuilder circuit
    ) {
        var r1cs = circuit.compileR1CS(CurveId.BLS12_381);
        List<R1CSConstraint> constraints = r1cs.constraints();
        int domainLog = 1;
        while ((1 << domainLog) < constraints.size() + r1cs.numPublicInputs() + 1) {
            domainLog++;
        }
        long started = System.nanoTime();
        BigInteger tau = PowersOfTauBLS381.generate(domainLog + 2).tauScalar();
        Groth16Keys keys = Groth16Keys.setupInMemory(
                constraints, r1cs.numWires(), r1cs.numPublicInputs(), tau);
        return new EutxoGroth16DevelopmentSetup(
                r1cs, constraints, keys,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    public ProofArtifact prove(
            EutxoZkPublicInputs publicInputs,
            BigInteger ownerSecret
    ) {
        return prove(
                publicInputs,
                EutxoKeyPaymentCircuit.witness(publicInputs, ownerSecret));
    }

    ProofArtifact prove(
            EutxoZkPublicInputs publicInputs,
            BigInteger[] witness
    ) {
        Objects.requireNonNull(publicInputs, "publicInputs");
        Objects.requireNonNull(witness, "witness");
        long started = System.nanoTime();
        Groth16ProofBLS381 proof = keys.prove(
                witness, constraints);
        long proofMillis = Duration.ofNanos(
                System.nanoTime() - started).toMillis();
        return new ProofArtifact(
                publicInputs,
                proof,
                ProverToCardano.compressProof(proof),
                proofMillis);
    }

    public boolean verify(ProofArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        List<BigInteger> inputs = artifact.publicInputs().ordered();
        G1Point vkX = toG1(keys.ic()[0]);
        for (int index = 0; index < inputs.size(); index++) {
            vkX = vkX.add(toG1(keys.ic()[index + 1])
                    .scalarMul(inputs.get(index)));
        }
        Groth16ProofBLS381 proof = artifact.proof();
        return BLS12381Pairing.pairingCheck(
                new G1Point[]{
                        toG1(proof.a()),
                        toG1(keys.pk().alphaG1()).negate(),
                        vkX.negate(),
                        toG1(proof.c()).negate()
                },
                new G2Point[]{
                        toG2(proof.b()),
                        toG2(keys.pk().betaG2()),
                        toG2(keys.gammaG2()),
                        toG2(keys.pk().deltaG2())
                });
    }

    public SnarkjsToCardano.VkCompressed compressedVerificationKey() {
        return new SnarkjsToCardano.VkCompressed(
                ProverToCardano.g1Compress(keys.pk().alphaG1()),
                ProverToCardano.g2Compress(keys.pk().betaG2()),
                ProverToCardano.g2Compress(keys.gammaG2()),
                ProverToCardano.g2Compress(keys.pk().deltaG2()),
                java.util.Arrays.stream(keys.ic())
                        .map(ProverToCardano::g1Compress)
                        .toList());
    }

    public int constraintCount() {
        return constraints.size();
    }

    public int wireCount() {
        return r1cs.numWires();
    }

    public int publicInputCount() {
        return r1cs.numPublicInputs();
    }

    public long setupMillis() {
        return setupMillis;
    }

    @Override
    public void close() {
        keys.close();
    }

    private static G1Point toG1(
            com.bloxbean.cardano.zeroj.bls12381.ec.JacobianG1BLS381.AffineG1 point
    ) {
        if (point.isInfinity()) {
            return G1Point.INFINITY;
        }
        return new G1Point(Fp.of(point.xBigInt()), Fp.of(point.yBigInt()));
    }

    private static G2Point toG2(
            com.bloxbean.cardano.zeroj.bls12381.ec.JacobianG2BLS381.AffineG2 point
    ) {
        if (point.isInfinity()) {
            return G2Point.INFINITY;
        }
        return new G2Point(
                Fp2.of(Fp.of(point.x().reBigInt()), Fp.of(point.x().imBigInt())),
                Fp2.of(Fp.of(point.y().reBigInt()), Fp.of(point.y().imBigInt())));
    }

    public record ProofArtifact(
            EutxoZkPublicInputs publicInputs,
            Groth16ProofBLS381 proof,
            SnarkjsToCardano.ProofCompressed compressedProof,
            long proofMillis
    ) {
        public ProofArtifact {
            Objects.requireNonNull(publicInputs, "publicInputs");
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(compressedProof, "compressedProof");
            if (proofMillis < 0) {
                throw new IllegalArgumentException("proof duration cannot be negative");
            }
        }
    }
}
