package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;

/** Pluggable node-local proof backend. It is never called by consensus apply. */
public interface EutxoProofBackend extends AutoCloseable {
    EutxoZkVerificationKey verificationKey();

    EutxoZkProofArtifact prove(
            EutxoZkStatement statement,
            EutxoKeyPaymentBatch witness,
            String proverId);

    boolean verify(EutxoZkProofArtifact artifact);

    @Override
    default void close() {
    }
}
