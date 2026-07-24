package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProof;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchVerificationKey;
import com.bloxbean.cardano.zeroj.bls12381.Bls12381Codecs;
import com.bloxbean.cardano.zeroj.bls12381.ec.G1Point;
import com.bloxbean.cardano.zeroj.bls12381.ec.G2Point;
import com.bloxbean.cardano.zeroj.bls12381.pairing.BLS12381Pairing;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.codec.SnarkjsToCardano;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Explicitly unsafe single-participant setup and prover for the fixed Jubjub
 * development batch profile.
 */
public final class EutxoJubjubBatchDevelopmentSetup implements AutoCloseable {
    private final EutxoZkBatchProfile profile;
    private final EutxoGroth16DevelopmentSetup delegate;
    private final EutxoZkBatchVerificationKey verificationKey;

    private EutxoJubjubBatchDevelopmentSetup(
            EutxoZkBatchProfile profile,
            EutxoGroth16DevelopmentSetup delegate
    ) {
        this.profile = requireMeasured(profile);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.verificationKey = toVerificationKey(
                profile, delegate.compressedVerificationKey());
    }

    public static EutxoJubjubBatchDevelopmentSetup create(
            EutxoZkBatchProfile profile,
            Path keyDirectory
    ) {
        profile = requireMeasured(profile);
        return new EutxoJubjubBatchDevelopmentSetup(
                profile,
                EutxoGroth16DevelopmentSetup.create(
                        EutxoJubjubBatchCircuit.circuit(profile),
                        Objects.requireNonNull(keyDirectory, "keyDirectory")));
    }

    public static EutxoJubjubBatchDevelopmentSetup load(
            EutxoZkBatchProfile profile,
            Path keyDirectory,
            EutxoCeremonyManifest manifest
    ) {
        profile = requireMeasured(profile);
        EutxoCeremonyBundleVerifier.verifyBeforeLoad(
                keyDirectory, manifest, profile.digest(),
                profile.circuitId());
        EutxoJubjubBatchDevelopmentSetup setup =
                new EutxoJubjubBatchDevelopmentSetup(
                        profile,
                        EutxoGroth16DevelopmentSetup.load(
                                EutxoJubjubBatchCircuit.circuit(profile),
                                keyDirectory));
        try {
            EutxoCeremonyBundleVerifier.verifyAfterLoad(
                    setup.verificationKey.digestHex(), manifest);
            return setup;
        } catch (RuntimeException failure) {
            setup.close();
            throw failure;
        }
    }

    public EutxoZkBatchProof prove(
            byte[] previousRoot,
            List<EutxoL2Transaction> transactions
    ) {
        var statement = EutxoJubjubBatchCircuit.statement(
                profile, previousRoot, transactions);
        var witness = EutxoJubjubBatchCircuit.witness(
                profile, statement, transactions);
        var generated = delegate.prove(statement.ordered(), witness);
        if (!delegate.verify(generated)) {
            throw new IllegalStateException(
                    "ZeroJ rejected the generated Jubjub batch proof");
        }
        var compressed = generated.compressedProof();
        return new EutxoZkBatchProof(
                profile.id(),
                profile.digest(),
                profile.authorizationProfile(),
                verificationKey.digestHex(),
                statement.ordered(),
                transactions.stream()
                        .map(EutxoL2Transaction::transactionId)
                        .toList(),
                compressed.piA(),
                compressed.piB(),
                compressed.piC(),
                generated.proofMillis());
    }

    public EutxoZkBatchVerificationKey verificationKey() {
        return verificationKey;
    }

    public EutxoZkBatchProfile profile() {
        return profile;
    }

    public int constraintCount() {
        return delegate.constraintCount();
    }

    public int wireCount() {
        return delegate.wireCount();
    }

    public long setupMillis() {
        return delegate.setupMillis();
    }

    public static boolean verify(
            EutxoZkBatchProof proof,
            EutxoZkBatchVerificationKey key
    ) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(key, "key");
        if (!proof.verificationKeyDigest().equals(key.digestHex())
                || !proof.batchProfileId().equals(key.batchProfileId())
                || !proof.batchProfileDigest().equals(
                key.batchProfileDigest())
                || !proof.authorizationProfile().equals(
                key.authorizationProfile())) {
            return false;
        }
        try {
            G1Point vkX = Bls12381Codecs.g1FromCompressed(
                    key.ic().getFirst());
            for (int index = 0;
                 index < proof.publicInputs().size();
                 index++) {
                vkX = vkX.add(Bls12381Codecs.g1FromCompressed(
                        key.ic().get(index + 1)).scalarMul(
                        proof.publicInputs().get(index)));
            }
            return BLS12381Pairing.pairingCheck(
                    new G1Point[]{
                            Bls12381Codecs.g1FromCompressed(proof.piA()),
                            Bls12381Codecs.g1FromCompressed(
                                    key.alpha()).negate(),
                            vkX.negate(),
                            Bls12381Codecs.g1FromCompressed(
                                    proof.piC()).negate()
                    },
                    new G2Point[]{
                            Bls12381Codecs.g2FromCompressed(proof.piB()),
                            Bls12381Codecs.g2FromCompressed(key.beta()),
                            Bls12381Codecs.g2FromCompressed(key.gamma()),
                            Bls12381Codecs.g2FromCompressed(key.delta())
                    });
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static EutxoZkBatchVerificationKey toVerificationKey(
            EutxoZkBatchProfile profile,
            SnarkjsToCardano.VkCompressed key
    ) {
        return new EutxoZkBatchVerificationKey(
                profile.id(),
                profile.digest(),
                profile.authorizationProfile(),
                profile.circuitId(),
                key.alpha(),
                key.beta(),
                key.gamma(),
                key.delta(),
                key.ic());
    }

    private static EutxoZkBatchProfile requireMeasured(
            EutxoZkBatchProfile profile
    ) {
        Objects.requireNonNull(profile, "profile");
        if (profile.status()
                != EutxoZkBatchProfile.Status.MEASURED_DEVELOPMENT_DEFAULT) {
            throw new IllegalArgumentException(
                    "only the measured development batch profile is selectable");
        }
        return profile;
    }
}
