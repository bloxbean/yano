package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;
import com.bloxbean.cardano.zeroj.bls12381.Bls12381Codecs;
import com.bloxbean.cardano.zeroj.bls12381.ec.G1Point;
import com.bloxbean.cardano.zeroj.bls12381.ec.G2Point;
import com.bloxbean.cardano.zeroj.bls12381.pairing.BLS12381Pairing;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.codec.SnarkjsToCardano;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Bounded-batch proof engine used by the durable prover service.
 *
 * <p>The factory intentionally names the single-participant setup. It is
 * suitable for local/devnet operation only and remains guarded by ZeroJ's
 * insecure-setup system property. Production ceremony import is a Z6 gate.</p>
 */
public final class EutxoBatchProofEngine implements AutoCloseable {
    private final EutxoSettlementGroth16DevelopmentSetup setup;
    private final EutxoZkVerificationKey verificationKey;

    private EutxoBatchProofEngine(
            EutxoSettlementGroth16DevelopmentSetup setup
    ) {
        this.setup = setup;
        this.verificationKey = toVerificationKey(
                setup.compressedVerificationKey());
    }

    public static EutxoBatchProofEngine singleParticipantDevelopmentSetup() {
        return new EutxoBatchProofEngine(
                EutxoSettlementGroth16DevelopmentSetup.create());
    }

    public static EutxoBatchProofEngine singleParticipantDevelopmentSetup(
            Path keyDirectory
    ) {
        return new EutxoBatchProofEngine(
                EutxoSettlementGroth16DevelopmentSetup.create(keyDirectory));
    }

    public static EutxoBatchProofEngine loadCeremonyBundle(
            Path keyDirectory,
            EutxoCeremonyManifest manifest
    ) {
        EutxoCeremonyBundleVerifier.verifyBeforeLoad(keyDirectory, manifest);
        EutxoBatchProofEngine engine = new EutxoBatchProofEngine(
                EutxoSettlementGroth16DevelopmentSetup.load(keyDirectory));
        try {
            EutxoCeremonyBundleVerifier.verifyAfterLoad(
                    engine.verificationKey(), manifest);
            return engine;
        } catch (RuntimeException failure) {
            engine.close();
            throw failure;
        }
    }

    public EutxoZkVerificationKey verificationKey() {
        return verificationKey;
    }

    public EutxoZkProofArtifact prove(
            EutxoZkStatement statement,
            EutxoKeyPaymentBatch witness,
            String proverId
    ) {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(witness, "witness");
        EutxoZkBatchData batchData =
                new EutxoZkBatchData(witness.payments());
        if (!java.util.Arrays.equals(
                batchData.commitment(), statement.batchDataCommitment())) {
            throw new IllegalArgumentException(
                    "witness batch data does not match the public statement");
        }
        if (!EutxoKeyPaymentSettlementCircuit.commitmentScalar(
                statement.batchDataCommitment()).equals(
                statement.publicInputs().batchDataCommitment())) {
            throw new IllegalArgumentException(
                    "batch-data scalar does not match the public statement");
        }
        byte[] keyDigest = java.util.HexFormat.of().parseHex(
                verificationKey.digestHex());
        var expectedContext =
                EutxoKeyPaymentSettlementCircuit.commitmentScalar(
                        EutxoKeyPaymentSettlementCircuit.settlementContext(
                                statement.chainId(),
                                statement.bridgeEpoch(),
                                keyDigest));
        if (!expectedContext.equals(
                statement.publicInputs().settlementContext())) {
            throw new IllegalArgumentException(
                    "settlement context does not match chain, epoch, profile, and key");
        }
        if (!statement.profile().equals(
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT)) {
            throw new IllegalArgumentException(
                    "statement does not select the settlement profile");
        }
        var expectedInputs = EutxoKeyPaymentBatchCircuit.publicInputs(
                scalarBytes(statement.publicInputs().previousRoot()), witness);
        if (!expectedInputs.equals(statement.publicInputs().batchInputs())) {
            throw new IllegalArgumentException(
                    "witness does not produce the public statement");
        }
        var generated = setup.prove(statement.publicInputs(), witness);
        if (!setup.verify(generated)) {
            throw new IllegalStateException("ZeroJ rejected the generated proof");
        }
        var compressed = generated.compressedProof();
        return new EutxoZkProofArtifact(
                statement.digestHex(),
                verificationKey.digestHex(),
                proverId,
                statement,
                compressed.piA(),
                compressed.piB(),
                compressed.piC(),
                generated.proofMillis());
    }

    public boolean verify(EutxoZkProofArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (!verificationKey.digestHex().equals(
                artifact.verificationKeyDigest())) {
            return false;
        }
        try {
            G1Point vkX = Bls12381Codecs.g1FromCompressed(
                    verificationKey.ic().getFirst());
            for (int index = 0;
                    index < artifact.statement().publicInputs().ordered().size();
                    index++) {
                G1Point point = Bls12381Codecs.g1FromCompressed(
                        verificationKey.ic().get(index + 1));
                vkX = vkX.add(point.scalarMul(
                        artifact.statement().publicInputs().ordered().get(index)));
            }
            return BLS12381Pairing.pairingCheck(
                    new G1Point[]{
                            Bls12381Codecs.g1FromCompressed(artifact.piA()),
                            Bls12381Codecs.g1FromCompressed(
                                    verificationKey.alpha()).negate(),
                            vkX.negate(),
                            Bls12381Codecs.g1FromCompressed(
                                    artifact.piC()).negate()
                    },
                    new G2Point[]{
                            Bls12381Codecs.g2FromCompressed(artifact.piB()),
                            Bls12381Codecs.g2FromCompressed(verificationKey.beta()),
                            Bls12381Codecs.g2FromCompressed(verificationKey.gamma()),
                            Bls12381Codecs.g2FromCompressed(verificationKey.delta())
                    });
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    @Override
    public void close() {
        setup.close();
    }

    private static EutxoZkVerificationKey toVerificationKey(
            SnarkjsToCardano.VkCompressed compressed
    ) {
        return new EutxoZkVerificationKey(
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.id(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.circuitId(),
                compressed.alpha(),
                compressed.beta(),
                compressed.gamma(),
                compressed.delta(),
                compressed.ic());
    }

    private static byte[] scalarBytes(BigInteger scalar) {
        byte[] encoded = scalar.toByteArray();
        int offset = encoded.length == 33 && encoded[0] == 0 ? 1 : 0;
        byte[] fixed = new byte[32];
        System.arraycopy(
                encoded, offset, fixed, fixed.length - (encoded.length - offset),
                encoded.length - offset);
        return fixed;
    }
}
