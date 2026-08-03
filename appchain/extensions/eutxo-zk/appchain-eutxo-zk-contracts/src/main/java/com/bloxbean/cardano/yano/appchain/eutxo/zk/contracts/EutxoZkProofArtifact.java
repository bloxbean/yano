package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Objects;

/** Canonical ZeroJ-neutral compressed Groth16 proof envelope. */
public record EutxoZkProofArtifact(
        String statementDigest,
        String verificationKeyDigest,
        String proverId,
        EutxoZkStatement statement,
        byte[] piA,
        byte[] piB,
        byte[] piC,
        long proofMillis
) {
    private static final int VERSION = 1;

    public EutxoZkProofArtifact {
        requireDigest(statementDigest, "statementDigest");
        requireDigest(verificationKeyDigest, "verificationKeyDigest");
        if (proverId == null || proverId.isBlank() || proverId.length() > 128) {
            throw new IllegalArgumentException("invalid prover id");
        }
        Objects.requireNonNull(statement, "statement");
        if (!statement.digestHex().equals(statementDigest)) {
            throw new IllegalArgumentException("statement digest does not match statement");
        }
        piA = requireBytes(piA, 48, "piA");
        piB = requireBytes(piB, 96, "piB");
        piC = requireBytes(piC, 48, "piC");
        if (proofMillis < 0) {
            throw new IllegalArgumentException("proof duration cannot be negative");
        }
    }

    @Override
    public byte[] piA() {
        return piA.clone();
    }

    @Override
    public byte[] piB() {
        return piB.clone();
    }

    @Override
    public byte[] piC() {
        return piC.clone();
    }

    public byte[] canonicalBytes() {
        byte[] statementBytes = statement.canonicalBytes();
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            EutxoZkCodec.writeText(output, statementDigest);
            EutxoZkCodec.writeText(output, verificationKeyDigest);
            EutxoZkCodec.writeText(output, proverId);
            output.writeInt(statementBytes.length);
            output.write(statementBytes);
            EutxoZkCodec.writeBytes(output, piA, 48);
            EutxoZkCodec.writeBytes(output, piB, 96);
            EutxoZkCodec.writeBytes(output, piC, 48);
            output.writeLong(proofMillis);
        });
    }

    public String digestHex() {
        return EutxoZkCodec.digestHex(canonicalBytes());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoZkProofArtifact artifact
                && java.util.Arrays.equals(
                canonicalBytes(), artifact.canonicalBytes());
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(canonicalBytes());
    }

    public static EutxoZkProofArtifact decode(byte[] encoded) {
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported proof-artifact version");
            }
            String statementDigest = EutxoZkCodec.readText(input);
            String verificationKeyDigest = EutxoZkCodec.readText(input);
            String proverId = EutxoZkCodec.readText(input);
            int statementLength = input.readInt();
            if (statementLength <= 0
                    || statementLength > EutxoZkCodec.MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("invalid embedded statement length");
            }
            EutxoZkStatement statement = EutxoZkStatement.decode(
                    input.readNBytes(statementLength));
            byte[] piA = EutxoZkCodec.readBytes(input, 48);
            byte[] piB = EutxoZkCodec.readBytes(input, 96);
            byte[] piC = EutxoZkCodec.readBytes(input, 48);
            long proofMillis = input.readLong();
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkProofArtifact(
                    statementDigest, verificationKeyDigest, proverId,
                    statement, piA, piB, piC, proofMillis);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid proof artifact", exception);
        }
    }

    private static byte[] requireBytes(byte[] value, int expected, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != expected) {
            throw new IllegalArgumentException(label + " has an invalid compressed length");
        }
        return value.clone();
    }

    private static void requireDigest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
    }
}
