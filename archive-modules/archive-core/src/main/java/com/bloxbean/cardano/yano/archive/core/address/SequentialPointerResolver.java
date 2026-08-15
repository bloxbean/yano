package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryOperation;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryStore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;

/**
 * Archive-private pointer-address resolver. Pointer registrations are replayed
 * sequentially and journaled with the dataset rows, so rollback never depends
 * on the authoritative account-state store being ahead of the archive worker.
 */
public final class SequentialPointerResolver {
    private final HotHistoryStore store;
    private final ArchiveDatasetId dataset;
    private final String namespace;

    public SequentialPointerResolver(HotHistoryStore store, ArchiveDatasetId dataset, String namespace) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        if (namespace == null || !namespace.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("pointer resolver namespace");
        }
        this.namespace = namespace;
    }

    public Optional<ResolvedStakeCredential> resolve(PointerCoordinate pointer) {
        return store.resolvePointer(dataset, namespace, pointer);
    }

    public List<HotHistoryOperation> putOperations(PointerCoordinate pointer, ResolvedStakeCredential credential) {
        return List.of(new HotHistoryOperation.PointerRegistered(namespace, pointer.slot(), pointer.txIndex(),
                pointer.certIndex(), credential.type(), credential.hash()));
    }

    public CredentialDeletion deleteCredential(ResolvedStakeCredential credential,
                                               PointerCoordinate deregistration) {
        List<PointerCoordinate> coordinates = store.pointersForCredential(dataset, namespace, credential);
        return new CredentialDeletion(coordinates, List.of(new HotHistoryOperation.PointerDeregistered(
                namespace, deregistration.slot(), deregistration.txIndex(), deregistration.certIndex(),
                credential.type(), credential.hash())));
    }

    public List<HotHistoryOperation> deleteOperations(PointerCoordinate pointer,
                                                      ResolvedStakeCredential credential) {
        return List.of(new HotHistoryOperation.PointerDeregistered(namespace,
                pointer.slot(), pointer.txIndex(), pointer.certIndex(), credential.type(), credential.hash()));
    }

    public record PointerCoordinate(long slot, int txIndex, int certIndex) {
        public PointerCoordinate {
            if (slot < 0 || txIndex < 0 || certIndex < 0) throw new IllegalArgumentException("negative pointer");
        }
    }

    public record CredentialDeletion(List<PointerCoordinate> coordinates,
                                     List<HotHistoryOperation> operations) {
        public CredentialDeletion {
            coordinates = List.copyOf(coordinates);
            operations = List.copyOf(operations);
        }
    }

    public record ResolvedStakeCredential(String type, byte[] hash) {
        public ResolvedStakeCredential {
            type = Objects.requireNonNull(type, "type");
            hash = Objects.requireNonNull(hash, "hash").clone();
            if (hash.length != 28) throw new IllegalArgumentException("stake credential must be 28 bytes");
        }

        @Override public byte[] hash() { return hash.clone(); }
    }
}
