package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical public statement proved by one bounded EUTxO batch. */
public record EutxoZkStatement(
        String chainId,
        long finalizedHeight,
        EutxoZkProfile profile,
        EutxoZkPublicInputs publicInputs,
        byte[] batchDataCommitment
) {
    private static final int VERSION = 1;

    public EutxoZkStatement {
        if (chainId == null || chainId.isBlank() || chainId.length() > 128) {
            throw new IllegalArgumentException("invalid chain id");
        }
        if (finalizedHeight < 1) {
            throw new IllegalArgumentException("finalized height must be positive");
        }
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(publicInputs, "publicInputs");
        batchDataCommitment = requireDigest(batchDataCommitment, "batchDataCommitment");
    }

    @Override
    public byte[] batchDataCommitment() {
        return batchDataCommitment.clone();
    }

    public byte[] canonicalBytes() {
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            EutxoZkCodec.writeText(output, chainId);
            output.writeLong(finalizedHeight);
            EutxoZkCodec.writeText(output, profile.id());
            output.writeInt(profile.version());
            EutxoZkCodec.writeText(output, profile.circuitId());
            EutxoZkCodec.writeText(output, profile.proofSystem());
            EutxoZkCodec.writeText(output, profile.curve());
            output.writeInt(profile.maximumBatchSize());
            output.writeInt(profile.maximumInputs());
            output.writeInt(profile.maximumOutputs());
            for (var scalar : publicInputs.ordered()) {
                EutxoZkCodec.writeScalar(output, scalar);
            }
            EutxoZkCodec.writeBytes(output, batchDataCommitment, 32);
        });
    }

    public String digestHex() {
        return EutxoZkCodec.digestHex(canonicalBytes());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoZkStatement statement
                && java.util.Arrays.equals(
                canonicalBytes(), statement.canonicalBytes());
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(canonicalBytes());
    }

    public static EutxoZkStatement decode(byte[] encoded) {
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported EUTxO ZK statement version");
            }
            String chainId = EutxoZkCodec.readText(input);
            long finalizedHeight = input.readLong();
            EutxoZkProfile profile = new EutxoZkProfile(
                    EutxoZkCodec.readText(input),
                    input.readInt(),
                    EutxoZkCodec.readText(input),
                    EutxoZkCodec.readText(input),
                    EutxoZkCodec.readText(input),
                    input.readInt(),
                    input.readInt(),
                    input.readInt());
            EutxoZkPublicInputs publicInputs = new EutxoZkPublicInputs(
                    EutxoZkCodec.readScalar(input),
                    EutxoZkCodec.readScalar(input),
                    EutxoZkCodec.readScalar(input),
                    EutxoZkCodec.readScalar(input),
                    EutxoZkCodec.readScalar(input));
            byte[] batchCommitment = EutxoZkCodec.readBytes(input, 32);
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkStatement(
                    chainId, finalizedHeight, profile, publicInputs, batchCommitment);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid EUTxO ZK statement", exception);
        }
    }

    public static byte[] digestOf(byte[] bytes) {
        return EutxoZkCodec.sha256(bytes);
    }

    public static byte[] digestFromHex(String hex) {
        byte[] digest;
        try {
            digest = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid SHA-256 digest", exception);
        }
        return requireDigest(digest, "digest");
    }

    private static byte[] requireDigest(byte[] digest, String label) {
        Objects.requireNonNull(digest, label);
        if (digest.length != 32) {
            throw new IllegalArgumentException(label + " must contain 32 bytes");
        }
        return digest.clone();
    }
}
