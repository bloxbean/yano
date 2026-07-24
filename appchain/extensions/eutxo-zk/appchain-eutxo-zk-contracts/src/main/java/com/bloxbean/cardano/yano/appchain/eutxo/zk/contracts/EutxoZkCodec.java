package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EutxoZkCodec {
    static final int MAX_ARTIFACT_BYTES = 1_048_576;
    static final int MAX_TEXT_BYTES = 256;

    private EutxoZkCodec() {
    }

    static byte[] encode(Encoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                encoder.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory EUTxO ZK encoding failed", impossible);
        }
    }

    static DataInputStream input(byte[] encoded) {
        if (encoded == null || encoded.length == 0
                || encoded.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("invalid EUTxO ZK artifact size");
        }
        return new DataInputStream(new ByteArrayInputStream(encoded));
    }

    static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            throw new IllegalArgumentException("trailing EUTxO ZK artifact bytes");
        }
    }

    static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("invalid EUTxO ZK text length");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    static String readText(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("invalid EUTxO ZK text length");
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    static void writeBytes(DataOutputStream output, byte[] value, int expected)
            throws IOException {
        if (value == null || value.length != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " bytes");
        }
        output.write(value);
    }

    static byte[] readBytes(DataInputStream input, int expected) throws IOException {
        byte[] value = input.readNBytes(expected);
        if (value.length != expected) {
            throw new IllegalArgumentException("truncated EUTxO ZK artifact");
        }
        return value;
    }

    static void writeSizedBytes(
            DataOutputStream output,
            byte[] value
    ) throws IOException {
        if (value == null || value.length == 0
                || value.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                    "invalid embedded artifact size");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    static byte[] readSizedBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                    "invalid embedded artifact size");
        }
        return readBytes(input, length);
    }

    static void writeScalar(DataOutputStream output, BigInteger value)
            throws IOException {
        if (value == null || value.signum() < 0 || value.bitLength() > 255) {
            throw new IllegalArgumentException("invalid scalar");
        }
        byte[] source = value.toByteArray();
        byte[] fixed = new byte[32];
        int sourceOffset = source.length == 33 && source[0] == 0 ? 1 : 0;
        int length = source.length - sourceOffset;
        System.arraycopy(source, sourceOffset, fixed, fixed.length - length, length);
        output.write(fixed);
    }

    static BigInteger readScalar(DataInputStream input) throws IOException {
        return new BigInteger(1, readBytes(input, 32));
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String digestHex(byte[] value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    @FunctionalInterface
    interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }
}
