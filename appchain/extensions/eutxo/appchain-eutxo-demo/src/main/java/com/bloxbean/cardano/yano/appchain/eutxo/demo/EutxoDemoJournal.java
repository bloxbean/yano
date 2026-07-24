package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Atomic, non-secret operation journal guarded by a single-owner file lock. */
public final class EutxoDemoJournal {
    public enum State {
        PLANNED,
        BUILT,
        SUBMITTED,
        OBSERVED,
        STABLE,
        RECONCILED,
        VERIFIED,
        FAILED_RETRYABLE,
        FAILED_TERMINAL
    }

    public record Entry(
            String operationId,
            String operation,
            String requestDigest,
            State state,
            Map<String, String> publicArtifacts,
            String diagnosticCode,
            String updatedAt
    ) {
        public Entry {
            publicArtifacts = publicArtifacts == null
                    ? Map.of() : Map.copyOf(publicArtifacts);
        }
    }

    private record Document(int schemaVersion, Map<String, Entry> operations) {
        private Document {
            operations = operations == null ? Map.of() : Map.copyOf(operations);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path journal;
    private final Path lock;

    public EutxoDemoJournal(Path workspace) {
        this.journal = workspace.resolve("runtime/journal/operations.json");
        this.lock = workspace.resolve("runtime/locks/operation.lock");
    }

    public Map<String, Entry> read() throws IOException {
        if (!Files.exists(journal)) {
            return Map.of();
        }
        Document document = JSON.readValue(journal.toFile(), Document.class);
        if (document.schemaVersion() != 1) {
            throw new IllegalStateException("unsupported EUTxO demo journal schema");
        }
        return document.operations();
    }

    public Entry plan(String operationId, String operation, String requestDigest)
            throws IOException {
        return update(operationId, operation, requestDigest, State.PLANNED, Map.of(), null);
    }

    public Entry advance(
            String operationId,
            String operation,
            String requestDigest,
            State state,
            Map<String, String> artifacts,
            String diagnosticCode) throws IOException {
        return update(operationId, operation, requestDigest, state, artifacts, diagnosticCode);
    }

    private Entry update(
            String operationId,
            String operation,
            String requestDigest,
            State state,
            Map<String, String> artifacts,
            String diagnosticCode) throws IOException {
        Files.createDirectories(journal.getParent());
        Files.createDirectories(lock.getParent());
        try (FileChannel channel = FileChannel.open(lock,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = requireLock(channel)) {
            Map<String, Entry> current = new LinkedHashMap<>(read());
            Entry prior = current.get(operationId);
            if (prior != null && (!prior.operation().equals(operation)
                    || !prior.requestDigest().equals(requestDigest))) {
                throw new IllegalStateException(
                        "operation ID is already bound to a different request");
            }
            if (prior != null && state.ordinal() < prior.state().ordinal()
                    && state != State.FAILED_RETRYABLE) {
                throw new IllegalStateException("operation journal state cannot move backwards");
            }
            Map<String, String> merged = new LinkedHashMap<>();
            if (prior != null) {
                merged.putAll(prior.publicArtifacts());
            }
            if (artifacts != null) {
                merged.putAll(artifacts);
            }
            Entry next = new Entry(operationId, operation, requestDigest, state,
                    merged, diagnosticCode, Instant.now().toString());
            current.put(operationId, next);
            write(new Document(1, current));
            return next;
        }
    }

    private static FileLock requireLock(FileChannel channel) throws IOException {
        FileLock acquired = channel.tryLock();
        if (acquired == null) {
            throw new IllegalStateException(
                    "another EUTxO demo operation owns this workspace");
        }
        return acquired;
    }

    private void write(Document document) throws IOException {
        Path temporary = journal.resolveSibling(journal.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document);
        EutxoDemoIdentityService.ownerFile(temporary);
        try {
            Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, journal, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
