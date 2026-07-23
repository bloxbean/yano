package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Canonical, bounded descriptor committed by consensus for asynchronous proving. */
public record EutxoValidityWitness(
        String engineId,
        byte[] previousRoot,
        byte[] nextRoot,
        byte[] transitionDigest,
        String transactionId,
        long appHeight,
        int ordinal
) {
    private static final int VERSION = 1;
    private static final int MAX_ID_BYTES = 128;

    public EutxoValidityWitness {
        engineId = requiredText(engineId, "engineId");
        transactionId = requiredText(transactionId, "transactionId");
        previousRoot = copy32(previousRoot, "previousRoot");
        nextRoot = copy32(nextRoot, "nextRoot");
        transitionDigest = copy32(transitionDigest, "transitionDigest");
        if (appHeight < 0 || ordinal < 0) {
            throw new IllegalArgumentException("negative witness position");
        }
    }

    @Override
    public byte[] previousRoot() {
        return previousRoot.clone();
    }

    @Override
    public byte[] nextRoot() {
        return nextRoot.clone();
    }

    @Override
    public byte[] transitionDigest() {
        return transitionDigest.clone();
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(192);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            writeText(output, engineId);
            output.write(previousRoot);
            output.write(nextRoot);
            output.write(transitionDigest);
            writeText(output, transactionId);
            output.writeLong(appHeight);
            output.writeInt(ordinal);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory witness encoding failed", impossible);
        }
    }

    public static EutxoValidityWitness decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > 16 * 1024) {
            throw new IllegalArgumentException("invalid witness descriptor length");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported witness descriptor version");
            }
            EutxoValidityWitness witness = new EutxoValidityWitness(
                    readText(input),
                    input.readNBytes(32),
                    input.readNBytes(32),
                    input.readNBytes(32),
                    readText(input),
                    input.readLong(),
                    input.readInt());
            if (input.available() != 0) {
                throw new IllegalArgumentException("trailing witness descriptor bytes");
            }
            return witness;
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid witness descriptor", failure);
        }
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof EutxoValidityWitness other
                && engineId.equals(other.engineId)
                && Arrays.equals(previousRoot, other.previousRoot)
                && Arrays.equals(nextRoot, other.nextRoot)
                && Arrays.equals(transitionDigest, other.transitionDigest)
                && transactionId.equals(other.transactionId)
                && appHeight == other.appHeight
                && ordinal == other.ordinal;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(engineId, transactionId, appHeight, ordinal);
        result = 31 * result + Arrays.hashCode(previousRoot);
        result = 31 * result + Arrays.hashCode(nextRoot);
        return 31 * result + Arrays.hashCode(transitionDigest);
    }

    private static void writeText(DataOutputStream output, String value)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_ID_BYTES) {
            throw new IllegalArgumentException("witness text exceeds " + MAX_ID_BYTES + " bytes");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_ID_BYTES) {
            throw new IllegalArgumentException("invalid witness text length");
        }
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new IllegalArgumentException("truncated witness text");
        }
        return requiredText(new String(encoded, StandardCharsets.UTF_8), "text");
    }

    private static String requiredText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static byte[] copy32(byte[] value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != 32) {
            throw new IllegalArgumentException(label + " must contain 32 bytes");
        }
        return Arrays.copyOf(value, value.length);
    }
}
