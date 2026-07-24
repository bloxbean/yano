package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Public, secret-free material needed by an independent relayer after a
 * proof has been produced.
 */
public record EutxoZkRecoveryBundle(
        EutxoZkBatchData batchData,
        EutxoZkProofArtifact proof,
        EutxoZkVerificationKey verificationKey
) {
    private static final int VERSION = 1;

    public EutxoZkRecoveryBundle {
        Objects.requireNonNull(batchData, "batchData");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(verificationKey, "verificationKey");
        if (!Arrays.equals(
                batchData.commitment(),
                proof.statement().batchDataCommitment())
                || !batchData.commitmentScalar().equals(
                proof.statement().publicInputs()
                        .batchDataCommitment())) {
            throw new IllegalArgumentException(
                    "recovery batch does not match proof statement");
        }
        if (!verificationKey.digestHex().equals(
                proof.verificationKeyDigest())) {
            throw new IllegalArgumentException(
                    "recovery verification key does not match proof");
        }
    }

    public String statementDigest() {
        return proof.statementDigest();
    }

    public byte[] canonicalBytes() {
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            EutxoZkCodec.writeSizedBytes(
                    output, batchData.canonicalBytes());
            EutxoZkCodec.writeSizedBytes(
                    output, proof.canonicalBytes());
            EutxoZkCodec.writeSizedBytes(
                    output, verificationKey.canonicalBytes());
        });
    }

    public static EutxoZkRecoveryBundle decode(byte[] encoded) {
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported recovery-bundle version");
            }
            EutxoZkBatchData batch = EutxoZkBatchData.decode(
                    EutxoZkCodec.readSizedBytes(input));
            EutxoZkProofArtifact proof = EutxoZkProofArtifact.decode(
                    EutxoZkCodec.readSizedBytes(input));
            EutxoZkVerificationKey key =
                    EutxoZkVerificationKey.decode(
                            EutxoZkCodec.readSizedBytes(input));
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkRecoveryBundle(batch, proof, key);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "invalid recovery bundle", exception);
        }
    }
}
