package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Deterministic, UI-safe projection of one finalized EUTxO attempt. */
public record EutxoTransactionSummary(
        String transactionId,
        String messageId,
        long sequence,
        long appHeight,
        int ordinal,
        long l1Slot,
        Status status,
        String authorizationProfile,
        List<Entry> inputs,
        List<Entry> outputs,
        String code
) {
    public static final int VERSION = 1;
    private static final int MAX_ENTRIES = 64;

    public enum Status { ACCEPTED, REJECTED }

    public record Entry(
            EutxoOutpoint outpoint,
            String address,
            BigInteger lovelace
    ) {
        public Entry {
            Objects.requireNonNull(outpoint, "outpoint");
            address = text(address, "address", 256);
            lovelace = Objects.requireNonNull(lovelace, "lovelace");
            if (lovelace.signum() < 0) {
                throw new IllegalArgumentException("lovelace cannot be negative");
            }
        }
    }

    public EutxoTransactionSummary {
        transactionId = transactionId == null ? "" : transactionId;
        if (!transactionId.isEmpty()) {
            new EutxoOutpoint(transactionId, 0);
        }
        messageId = hex(messageId, "messageId", 32);
        if (sequence < 1 || appHeight < 1 || ordinal < 0 || l1Slot < 0) {
            throw new IllegalArgumentException("invalid finalized position");
        }
        Objects.requireNonNull(status, "status");
        authorizationProfile = authorizationProfile == null
                ? "" : authorizationProfile.trim();
        if (authorizationProfile.length() > 63) {
            throw new IllegalArgumentException("authorization profile is too long");
        }
        inputs = entries(inputs);
        outputs = entries(outputs);
        code = code == null ? "" : code.trim();
        if (code.length() > 128) {
            throw new IllegalArgumentException("result code is too long");
        }
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeUTF(transactionId);
                output.writeUTF(messageId);
                output.writeLong(sequence);
                output.writeLong(appHeight);
                output.writeInt(ordinal);
                output.writeLong(l1Slot);
                output.writeByte(status.ordinal());
                output.writeUTF(authorizationProfile);
                writeEntries(output, inputs);
                writeEntries(output, outputs);
                output.writeUTF(code);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("summary encoding failed", impossible);
        }
    }

    public static EutxoTransactionSummary decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported summary version");
            }
            EutxoTransactionSummary value = new EutxoTransactionSummary(
                    input.readUTF(), input.readUTF(), input.readLong(),
                    input.readLong(), input.readInt(), input.readLong(),
                    Status.values()[input.readUnsignedByte()],
                    input.readUTF(), readEntries(input), readEntries(input),
                    input.readUTF());
            if (input.available() != 0) {
                throw new IllegalArgumentException("trailing summary bytes");
            }
            return value;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid EUTxO summary", failure);
        }
    }

    public static byte[] encodeList(List<EutxoTransactionSummary> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeInt(values.size());
                for (EutxoTransactionSummary value : values) {
                    byte[] encoded = value.encode();
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("summary list encoding failed", impossible);
        }
    }

    public static List<EutxoTransactionSummary> decodeList(byte[] encoded) {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported summary list");
            }
            int count = input.readInt();
            if (count < 0 || count > 100) {
                throw new IllegalArgumentException("invalid summary list count");
            }
            List<EutxoTransactionSummary> values = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int length = input.readInt();
                if (length < 1 || length > 128 * 1024) {
                    throw new IllegalArgumentException("invalid summary length");
                }
                values.add(decode(input.readNBytes(length)));
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("trailing summary list bytes");
            }
            return List.copyOf(values);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid summary list", failure);
        }
    }

    private static void writeEntries(
            DataOutputStream output,
            List<Entry> entries) throws IOException {
        output.writeInt(entries.size());
        for (Entry entry : entries) {
            output.writeUTF(entry.outpoint().transactionId());
            output.writeInt(entry.outpoint().index());
            output.writeUTF(entry.address());
            output.writeUTF(entry.lovelace().toString());
        }
    }

    private static List<Entry> readEntries(DataInputStream input)
            throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid summary entry count");
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new Entry(
                    new EutxoOutpoint(input.readUTF(), input.readInt()),
                    input.readUTF(), new BigInteger(input.readUTF())));
        }
        return List.copyOf(entries);
    }

    private static List<Entry> entries(List<Entry> values) {
        values = List.copyOf(Objects.requireNonNull(values, "entries"));
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many summary entries");
        }
        return values;
    }

    private static String hex(
            String value,
            String field,
            int bytes) {
        value = Objects.requireNonNull(value, field);
        if (value.length() != bytes * 2
                || !value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("invalid " + field);
        }
        HexFormat.of().parseHex(value);
        return value;
    }

    private static String text(
            String value,
            String field,
            int maximum) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
