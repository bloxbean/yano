package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Strict typed reader for validity statements, data, keys, and proofs. */
public final class EutxoZkClient {
    private final EutxoZkDataSource source;
    private final ProofVerifier verifier;

    public EutxoZkClient(
            EutxoZkDataSource source,
            ProofVerifier verifier
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public Status status(String statementDigest) {
        Optional<EutxoZkStatement> statement =
                source.statement(statementDigest);
        if (statement.isEmpty()) {
            return new Status(State.NOT_FOUND, "", "");
        }
        if (!statement.orElseThrow().digestHex().equals(statementDigest)) {
            return new Status(State.INVALID, "", "statement digest mismatch");
        }
        Optional<EutxoZkBatchData> batch =
                source.batchData(statementDigest);
        if (batch.isEmpty()) {
            return new Status(State.WAITING_FOR_DATA, "", "");
        }
        if (!Arrays.equals(
                batch.orElseThrow().commitment(),
                statement.orElseThrow().batchDataCommitment())
                || !batch.orElseThrow().commitmentScalar().equals(
                statement.orElseThrow().publicInputs()
                        .batchDataCommitment())) {
            return new Status(State.INVALID, "", "batch-data commitment mismatch");
        }
        Optional<EutxoZkProofArtifact> proof = source.proof(statementDigest);
        if (proof.isEmpty()) {
            return new Status(State.WAITING_FOR_PROOF, "", "");
        }
        EutxoZkProofArtifact artifact = proof.orElseThrow();
        if (!artifact.statement().digestHex().equals(
                statement.orElseThrow().digestHex())
                || !artifact.statementDigest().equals(statementDigest)) {
            return new Status(State.INVALID, "", "proof statement mismatch");
        }
        Optional<EutxoZkVerificationKey> key =
                source.verificationKey(artifact.verificationKeyDigest());
        if (key.isEmpty()
                || !key.orElseThrow().digestHex().equals(
                artifact.verificationKeyDigest())) {
            return new Status(State.WAITING_FOR_KEY,
                    artifact.digestHex(), "");
        }
        if (!verifier.verify(artifact, key.orElseThrow())) {
            return new Status(State.INVALID,
                    artifact.digestHex(), "proof verification failed");
        }
        return new Status(State.VERIFIED, artifact.digestHex(), "");
    }

    public enum State {
        NOT_FOUND,
        WAITING_FOR_DATA,
        WAITING_FOR_PROOF,
        WAITING_FOR_KEY,
        VERIFIED,
        INVALID
    }

    public record Status(State state, String proofDigest, String message) {
        public Status {
            Objects.requireNonNull(state, "state");
            proofDigest = proofDigest == null ? "" : proofDigest;
            message = message == null ? "" : message;
        }
    }

    @FunctionalInterface
    public interface ProofVerifier {
        boolean verify(
                EutxoZkProofArtifact artifact,
                EutxoZkVerificationKey verificationKey);
    }
}
