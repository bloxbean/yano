package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;

import java.util.Optional;

/** Transport-neutral source for REST, file, relay, or embedded clients. */
public interface EutxoZkDataSource {
    Optional<EutxoZkStatement> statement(String statementDigest);

    Optional<EutxoZkBatchData> batchData(String statementDigest);

    Optional<EutxoZkProofArtifact> proof(String statementDigest);

    Optional<EutxoZkVerificationKey> verificationKey(
            String verificationKeyDigest);
}
