package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.codec.SnarkjsToCardano;

import java.nio.file.Path;
import java.util.Objects;

/** Development-ceremony facade for the Z3 settlement-bound circuit. */
final class EutxoSettlementGroth16DevelopmentSetup
        implements AutoCloseable {
    private final EutxoGroth16DevelopmentSetup delegate;

    private EutxoSettlementGroth16DevelopmentSetup(
            EutxoGroth16DevelopmentSetup delegate
    ) {
        this.delegate = delegate;
    }

    static EutxoSettlementGroth16DevelopmentSetup create() {
        return new EutxoSettlementGroth16DevelopmentSetup(
                EutxoGroth16DevelopmentSetup.create(
                        EutxoKeyPaymentSettlementCircuit.circuit()));
    }

    static EutxoSettlementGroth16DevelopmentSetup create(Path keyDirectory) {
        return new EutxoSettlementGroth16DevelopmentSetup(
                EutxoGroth16DevelopmentSetup.create(
                        EutxoKeyPaymentSettlementCircuit.circuit(),
                        keyDirectory));
    }

    static EutxoSettlementGroth16DevelopmentSetup load(Path keyDirectory) {
        return new EutxoSettlementGroth16DevelopmentSetup(
                EutxoGroth16DevelopmentSetup.load(
                        EutxoKeyPaymentSettlementCircuit.circuit(),
                        keyDirectory));
    }

    EutxoGroth16DevelopmentSetup.SettlementProofArtifact prove(
            EutxoZkSettlementPublicInputs inputs,
            EutxoKeyPaymentBatch batch
    ) {
        Objects.requireNonNull(batch, "batch");
        return delegate.prove(
                inputs,
                EutxoKeyPaymentSettlementCircuit.witness(inputs, batch));
    }

    boolean verify(
            EutxoGroth16DevelopmentSetup.SettlementProofArtifact proof
    ) {
        return delegate.verify(proof);
    }

    SnarkjsToCardano.VkCompressed compressedVerificationKey() {
        return delegate.compressedVerificationKey();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
