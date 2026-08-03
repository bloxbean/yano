package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.codec.SnarkjsToCardano;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Objects;

/** Development-only Groth16 setup facade for the Z1 bounded batch circuit. */
public final class EutxoBatchGroth16DevelopmentSetup implements AutoCloseable {
    private final EutxoGroth16DevelopmentSetup delegate;

    private EutxoBatchGroth16DevelopmentSetup(
            EutxoGroth16DevelopmentSetup delegate
    ) {
        this.delegate = delegate;
    }

    public static EutxoBatchGroth16DevelopmentSetup create() {
        return new EutxoBatchGroth16DevelopmentSetup(
                EutxoGroth16DevelopmentSetup.create(
                        EutxoKeyPaymentBatchCircuit.circuit()));
    }

    public static EutxoBatchGroth16DevelopmentSetup create(
            Path keyDirectory
    ) {
        return new EutxoBatchGroth16DevelopmentSetup(
                EutxoGroth16DevelopmentSetup.create(
                        EutxoKeyPaymentBatchCircuit.circuit(),
                        keyDirectory));
    }

    public static EutxoBatchGroth16DevelopmentSetup load(
            Path keyDirectory
    ) {
        return new EutxoBatchGroth16DevelopmentSetup(
                EutxoGroth16DevelopmentSetup.load(
                        EutxoKeyPaymentBatchCircuit.circuit(),
                        keyDirectory));
    }

    public EutxoGroth16DevelopmentSetup.ProofArtifact prove(
            EutxoZkPublicInputs inputs,
            EutxoKeyPaymentBatch batch
    ) {
        Objects.requireNonNull(batch, "batch");
        return delegate.prove(
                inputs,
                EutxoKeyPaymentBatchCircuit.witness(inputs, batch));
    }

    public boolean verify(EutxoGroth16DevelopmentSetup.ProofArtifact proof) {
        return delegate.verify(proof);
    }

    EutxoGroth16DevelopmentSetup.ProofArtifact proveUnchecked(
            EutxoZkPublicInputs inputs,
            BigInteger[] witness
    ) {
        return delegate.prove(inputs, witness);
    }

    public SnarkjsToCardano.VkCompressed compressedVerificationKey() {
        return delegate.compressedVerificationKey();
    }

    public int constraintCount() {
        return delegate.constraintCount();
    }

    public int wireCount() {
        return delegate.wireCount();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
