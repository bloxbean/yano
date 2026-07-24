package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** ZeroJ-neutral compressed Groth16 verification-key artifact. */
public record EutxoZkVerificationKey(
        String profileId,
        String circuitId,
        byte[] alpha,
        byte[] beta,
        byte[] gamma,
        byte[] delta,
        List<byte[]> ic
) {
    private static final int VERSION = 2;

    public EutxoZkVerificationKey {
        requireIdentity(profileId, "profileId");
        requireIdentity(circuitId, "circuitId");
        alpha = requireBytes(alpha, 48, "alpha");
        beta = requireBytes(beta, 96, "beta");
        gamma = requireBytes(gamma, 96, "gamma");
        delta = requireBytes(delta, 96, "delta");
        Objects.requireNonNull(ic, "ic");
        if (ic.size() != EutxoZkSettlementPublicInputs.COUNT + 1) {
            throw new IllegalArgumentException(
                    "verification key has an invalid public-input basis");
        }
        List<byte[]> copied = new ArrayList<>(ic.size());
        for (byte[] point : ic) {
            copied.add(requireBytes(point, 48, "ic"));
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
            EutxoZkCodec.writeText(output, profileId);
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
        return other instanceof EutxoZkVerificationKey key
                && java.util.Arrays.equals(
                canonicalBytes(), key.canonicalBytes());
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(canonicalBytes());
    }

    public static EutxoZkVerificationKey decode(byte[] encoded) {
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported verification-key version");
            }
            String profileId = EutxoZkCodec.readText(input);
            String circuitId = EutxoZkCodec.readText(input);
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
            return new EutxoZkVerificationKey(
                    profileId, circuitId, alpha, beta, gamma, delta, ic);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid verification-key artifact", exception);
        }
    }

    private static byte[] requireBytes(byte[] value, int expected, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != expected) {
            throw new IllegalArgumentException(label + " has an invalid compressed length");
        }
        return value.clone();
    }

    private static void requireIdentity(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }
}
