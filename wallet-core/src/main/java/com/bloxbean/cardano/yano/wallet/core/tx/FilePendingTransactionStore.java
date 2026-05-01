package com.bloxbean.cardano.yano.wallet.core.tx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FilePendingTransactionStore implements PendingTransactionStore {
    private static final TypeReference<List<PendingTransaction>> TRANSACTION_LIST =
            new TypeReference<>() {
            };

    private final Path file;
    private final ObjectMapper objectMapper;

    public FilePendingTransactionStore(Path file) {
        this(file, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    FilePendingTransactionStore(Path file, ObjectMapper objectMapper) {
        this.file = file.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized PendingTransaction save(PendingTransaction transaction) {
        Map<String, PendingTransaction> byHash = new LinkedHashMap<>();
        for (PendingTransaction existing : readAll()) {
            byHash.put(existing.txHash(), existing);
        }
        byHash.put(transaction.txHash(), transaction);
        writeAll(new ArrayList<>(byHash.values()));
        return transaction;
    }

    @Override
    public synchronized Optional<PendingTransaction> find(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            return Optional.empty();
        }
        return readAll().stream()
                .filter(tx -> txHash.equals(tx.txHash()))
                .findFirst();
    }

    @Override
    public synchronized List<PendingTransaction> list() {
        return readAll().stream()
                .sorted(Comparator.comparingLong(PendingTransaction::createdAtEpochMillis).reversed())
                .toList();
    }

    private List<PendingTransaction> readAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(file.toFile(), TRANSACTION_LIST);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read pending transactions from " + file, e);
        }
    }

    private void writeAll(List<PendingTransaction> transactions) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), transactions);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write pending transactions to " + file, e);
        }
    }
}
