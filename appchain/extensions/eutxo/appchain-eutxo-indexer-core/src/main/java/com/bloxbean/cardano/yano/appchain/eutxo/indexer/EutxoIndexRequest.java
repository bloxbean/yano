package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Fixed bounded request exchanged only across the host local-model seam. */
public record EutxoIndexRequest(
        long before,
        int limit,
        int depth,
        int maximumNodes,
        String id,
        String address,
        String status
) {
    private static final int VERSION = 1;

    public EutxoIndexRequest {
        id = bounded(id, 256, "id");
        address = bounded(address, 256, "address");
        status = bounded(status, 64, "status");
        if (before < 0 || limit < 1 || limit > 100
                || depth < 0 || depth > 6
                || maximumNodes < 1 || maximumNodes > 256) {
            throw new IllegalArgumentException("invalid EUTxO index request bounds");
        }
    }

    public static EutxoIndexRequest defaults() {
        return new EutxoIndexRequest(0, 25, 2, 256, "", "", "");
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeLong(before);
                output.writeInt(limit);
                output.writeInt(depth);
                output.writeInt(maximumNodes);
                write(output, id);
                write(output, address);
                write(output, status);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static EutxoIndexRequest decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < 32 || encoded.length > 2_048) {
            throw new IllegalArgumentException("invalid EUTxO index request");
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported EUTxO index request");
            }
            EutxoIndexRequest request = new EutxoIndexRequest(
                    input.readLong(),
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    read(input, 256),
                    read(input, 256),
                    read(input, 64));
            if (input.available() != 0) {
                throw new IllegalArgumentException(
                        "trailing EUTxO index request bytes");
            }
            return request;
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "invalid EUTxO index request", failure);
        }
    }

    private static void write(DataOutputStream output, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input, int maximum)
            throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IllegalArgumentException(
                    "invalid EUTxO index request field");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException(
                    "truncated EUTxO index request");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String bounded(
            String value,
            int maximum,
            String field
    ) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
}
