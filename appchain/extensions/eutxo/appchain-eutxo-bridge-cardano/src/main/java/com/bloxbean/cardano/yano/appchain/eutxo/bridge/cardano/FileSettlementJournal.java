package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Small crash-safe journal that stores one atomically replaced record per
 * claim. Exact signed transaction bytes are forced before they can be
 * submitted.
 */
public final class FileSettlementJournal implements SettlementJournal {
    private static final int MAGIC = 0x5945534A;
    private static final int VERSION = 1;
    private static final int MAX_TRANSACTION_BYTES = 1024 * 1024;
    private static final int MAX_DETAIL_BYTES = 8 * 1024;

    private final Path directory;

    public FileSettlementJournal(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot create the EUTxO settlement journal", failure);
        }
    }

    @Override
    public synchronized Optional<Entry> find(String claimId) {
        Path path = entryPath(claimId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            byte[] encoded = Files.readAllBytes(path);
            if (encoded.length > MAX_TRANSACTION_BYTES + MAX_DETAIL_BYTES + 1024) {
                throw new IllegalStateException("settlement journal entry exceeds its bound");
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(encoded))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw new IllegalStateException(
                            "unsupported settlement journal entry");
                }
                String retainedClaim = input.readUTF();
                String transactionId = input.readUTF();
                int stageOrdinal = input.readUnsignedByte();
                int transactionLength = boundedLength(
                        input.readInt(), MAX_TRANSACTION_BYTES, "transaction");
                byte[] transaction = input.readNBytes(transactionLength);
                int detailLength = boundedLength(
                        input.readInt(), MAX_DETAIL_BYTES, "detail");
                byte[] detail = input.readNBytes(detailLength);
                if (input.available() != 0
                        || stageOrdinal >= Stage.values().length
                        || !retainedClaim.equals(canonicalId(claimId))
                        || transaction.length == 0
                        || transaction.length != transactionLength
                        || detail.length != detailLength) {
                    throw new IllegalStateException(
                            "invalid settlement journal entry");
                }
                return Optional.of(new Entry(
                        retainedClaim,
                        transactionId,
                        transaction,
                        Stage.values()[stageOrdinal],
                        new String(detail, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot read the EUTxO settlement journal", failure);
        }
    }

    @Override
    public synchronized void save(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        String claimId = canonicalId(entry.claimId());
        byte[] transaction = entry.signedTransactionCbor();
        byte[] detail = entry.detail().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boundedLength(transaction.length, MAX_TRANSACTION_BYTES, "transaction");
        boundedLength(detail.length, MAX_DETAIL_BYTES, "detail");
        Path target = entryPath(claimId);
        Path temporary = directory.resolve(claimId + ".tmp");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(claimId);
                output.writeUTF(entry.transactionId());
                output.writeByte(entry.stage().ordinal());
                output.writeInt(transaction.length);
                output.write(transaction);
                output.writeInt(detail.length);
                output.write(detail);
            }
            Files.write(
                    temporary,
                    bytes.toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(
                    directory, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException unsupportedDirectoryFsync) {
                // The record itself was forced; some filesystems do not permit
                // opening directories as channels.
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot persist the EUTxO settlement journal", failure);
        }
    }

    private Path entryPath(String claimId) {
        return directory.resolve(canonicalId(claimId) + ".settlement");
    }

    private static String canonicalId(String value) {
        String normalized = Objects.requireNonNull(value, "claimId").trim();
        if (normalized.length() != 64
                || !normalized.equals(normalized.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "claimId must be 32-byte lowercase hex");
        }
        try {
            HexFormat.of().parseHex(normalized);
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "claimId must be 32-byte lowercase hex", failure);
        }
    }

    private static int boundedLength(int value, int maximum, String field) {
        if (value < 0 || value > maximum) {
            throw new IllegalStateException(
                    "settlement journal " + field + " exceeds its bound");
        }
        return value;
    }
}
