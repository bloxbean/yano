package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Constant-size proof plus public identity for one ordered L2 batch. */
public record EutxoZkBatchProof(
        String batchProfileId,
        String batchProfileDigest,
        String authorizationProfile,
        String verificationKeyDigest,
        List<BigInteger> publicInputs,
        List<String> transactionIds,
        byte[] piA,
        byte[] piB,
        byte[] piC,
        long proofMillis
) {
    private static final int VERSION = 1;

    public EutxoZkBatchProof {
        requireText(batchProfileId, "batchProfileId");
        requireDigest(batchProfileDigest, "batchProfileDigest");
        requireText(authorizationProfile, "authorizationProfile");
        requireDigest(verificationKeyDigest, "verificationKeyDigest");
        publicInputs = List.copyOf(Objects.requireNonNull(
                publicInputs, "publicInputs"));
        if (publicInputs.size() != EutxoZkSettlementPublicInputs.COUNT
                || publicInputs.stream().anyMatch(value ->
                value == null || value.signum() < 0 || value.bitLength() > 255)) {
            throw new IllegalArgumentException(
                    "batch proof requires eight canonical public scalars");
        }
        transactionIds = List.copyOf(Objects.requireNonNull(
                transactionIds, "transactionIds"));
        if (transactionIds.isEmpty() || transactionIds.size() > 64
                || transactionIds.stream().anyMatch(id ->
                id == null || !id.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "batch proof has an invalid transaction inventory");
        }
        piA = fixed(piA, 48, "piA");
        piB = fixed(piB, 96, "piB");
        piC = fixed(piC, 48, "piC");
        if (proofMillis < 0) {
            throw new IllegalArgumentException(
                    "proof duration cannot be negative");
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
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            EutxoZkCodec.writeText(output, batchProfileId);
            EutxoZkCodec.writeText(output, batchProfileDigest);
            EutxoZkCodec.writeText(output, authorizationProfile);
            EutxoZkCodec.writeText(output, verificationKeyDigest);
            output.writeByte(publicInputs.size());
            for (BigInteger input : publicInputs) {
                EutxoZkCodec.writeScalar(output, input);
            }
            output.writeByte(transactionIds.size());
            for (String transactionId : transactionIds) {
                EutxoZkCodec.writeText(output, transactionId);
            }
            EutxoZkCodec.writeBytes(output, piA, 48);
            EutxoZkCodec.writeBytes(output, piB, 96);
            EutxoZkCodec.writeBytes(output, piC, 48);
            output.writeLong(proofMillis);
        });
    }

    public String digestHex() {
        return EutxoZkCodec.digestHex(canonicalBytes());
    }

    public EutxoZkSettlementPublicInputs settlementInputs() {
        return new EutxoZkSettlementPublicInputs(
                publicInputs.get(0),
                publicInputs.get(1),
                publicInputs.get(2),
                publicInputs.get(3),
                publicInputs.get(4),
                publicInputs.get(5),
                publicInputs.get(6),
                publicInputs.get(7));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoZkBatchProof proof
                && java.util.Arrays.equals(
                canonicalBytes(), proof.canonicalBytes());
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(canonicalBytes());
    }

    public static EutxoZkBatchProof decode(byte[] encoded) {
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported batch-proof version");
            }
            String profileId = EutxoZkCodec.readText(input);
            String profileDigest = EutxoZkCodec.readText(input);
            String authorization = EutxoZkCodec.readText(input);
            String verificationKey = EutxoZkCodec.readText(input);
            int publicInputCount = input.readUnsignedByte();
            List<BigInteger> publicInputs = new ArrayList<>(publicInputCount);
            for (int index = 0; index < publicInputCount; index++) {
                publicInputs.add(EutxoZkCodec.readScalar(input));
            }
            int transactionCount = input.readUnsignedByte();
            List<String> transactionIds = new ArrayList<>(transactionCount);
            for (int index = 0; index < transactionCount; index++) {
                transactionIds.add(EutxoZkCodec.readText(input));
            }
            byte[] piA = EutxoZkCodec.readBytes(input, 48);
            byte[] piB = EutxoZkCodec.readBytes(input, 96);
            byte[] piC = EutxoZkCodec.readBytes(input, 48);
            long proofMillis = input.readLong();
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkBatchProof(
                    profileId, profileDigest, authorization,
                    verificationKey, publicInputs, transactionIds,
                    piA, piB, piC, proofMillis);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "invalid batch-proof artifact", exception);
        }
    }

    private static byte[] fixed(byte[] value, int length, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != length) {
            throw new IllegalArgumentException(label + " has an invalid length");
        }
        return value.clone();
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static void requireDigest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }
}
