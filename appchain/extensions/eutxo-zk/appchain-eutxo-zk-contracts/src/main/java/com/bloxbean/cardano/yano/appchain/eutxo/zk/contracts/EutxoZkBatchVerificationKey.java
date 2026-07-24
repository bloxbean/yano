package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Compressed Groth16 verification key for one immutable settlement-bound batch profile. */
public record EutxoZkBatchVerificationKey(
        String batchProfileId,
        String batchProfileDigest,
        String authorizationProfile,
        String circuitId,
        byte[] alpha,
        byte[] beta,
        byte[] gamma,
        byte[] delta,
        List<byte[]> ic
) {
    private static final int VERSION = 1;
    private static final int PUBLIC_INPUT_COUNT =
            EutxoZkSettlementPublicInputs.COUNT;

    public EutxoZkBatchVerificationKey {
        requireText(batchProfileId, "batchProfileId");
        requireDigest(batchProfileDigest, "batchProfileDigest");
        requireText(authorizationProfile, "authorizationProfile");
        requireText(circuitId, "circuitId");
        alpha = fixed(alpha, 48, "alpha");
        beta = fixed(beta, 96, "beta");
        gamma = fixed(gamma, 96, "gamma");
        delta = fixed(delta, 96, "delta");
        Objects.requireNonNull(ic, "ic");
        if (ic.size() != PUBLIC_INPUT_COUNT + 1) {
            throw new IllegalArgumentException(
                    "batch verification key must have nine IC points");
        }
        List<byte[]> copied = new ArrayList<>(ic.size());
        for (byte[] point : ic) {
            copied.add(fixed(point, 48, "ic"));
        }
        ic = List.copyOf(copied);
    }

    @Override
    public byte[] alpha() {
        return alpha.clone();
    }

    @Override
    public byte[] beta() {
        return beta.clone();
    }

    @Override
    public byte[] gamma() {
        return gamma.clone();
    }

    @Override
    public byte[] delta() {
        return delta.clone();
    }

    @Override
    public List<byte[]> ic() {
        return ic.stream().map(byte[]::clone).toList();
    }

    public byte[] canonicalBytes() {
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            EutxoZkCodec.writeText(output, batchProfileId);
            EutxoZkCodec.writeText(output, batchProfileDigest);
            EutxoZkCodec.writeText(output, authorizationProfile);
            EutxoZkCodec.writeText(output, circuitId);
            EutxoZkCodec.writeBytes(output, alpha, 48);
            EutxoZkCodec.writeBytes(output, beta, 96);
            EutxoZkCodec.writeBytes(output, gamma, 96);
            EutxoZkCodec.writeBytes(output, delta, 96);
            output.writeByte(ic.size());
            for (byte[] point : ic) {
                EutxoZkCodec.writeBytes(output, point, 48);
            }
        });
    }

    public String digestHex() {
        return EutxoZkCodec.digestHex(canonicalBytes());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoZkBatchVerificationKey key
                && java.util.Arrays.equals(
                canonicalBytes(), key.canonicalBytes());
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(canonicalBytes());
    }

    public static EutxoZkBatchVerificationKey decode(byte[] encoded) {
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported batch verification-key version");
            }
            String profileId = EutxoZkCodec.readText(input);
            String profileDigest = EutxoZkCodec.readText(input);
            String authorization = EutxoZkCodec.readText(input);
            String circuit = EutxoZkCodec.readText(input);
            byte[] alpha = EutxoZkCodec.readBytes(input, 48);
            byte[] beta = EutxoZkCodec.readBytes(input, 96);
            byte[] gamma = EutxoZkCodec.readBytes(input, 96);
            byte[] delta = EutxoZkCodec.readBytes(input, 96);
            int count = input.readUnsignedByte();
            List<byte[]> ic = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                ic.add(EutxoZkCodec.readBytes(input, 48));
            }
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkBatchVerificationKey(
                    profileId, profileDigest, authorization, circuit,
                    alpha, beta, gamma, delta, ic);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "invalid batch verification-key artifact", exception);
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
