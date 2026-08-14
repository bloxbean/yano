package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;

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
    private final RocksDbHotHistoryStore store;
    private final ArchiveDatasetId dataset;
    private final byte[] prefix;
    private final byte[] reversePrefix;

    public SequentialPointerResolver(RocksDbHotHistoryStore store, ArchiveDatasetId dataset, String namespace) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        if (namespace == null || !namespace.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("pointer resolver namespace");
        }
        this.prefix = ("pointer/" + namespace + "/").getBytes(StandardCharsets.UTF_8);
        this.reversePrefix = ("pointer-reverse/" + namespace + "/").getBytes(StandardCharsets.UTF_8);
    }

    public Optional<ResolvedStakeCredential> resolve(PointerCoordinate pointer) {
        return store.get(dataset, logicalKey(pointer)).map(SequentialPointerResolver::decode);
    }

    public List<HotHistoryMutation> putMutations(PointerCoordinate pointer, ResolvedStakeCredential credential) {
        byte[] pointerKey = logicalKey(pointer);
        return List.of(new HotHistoryMutation(pointerKey, encode(credential)),
                new HotHistoryMutation(reverseKey(credential, pointer), pointerKey));
    }

    public CredentialDeletion deleteCredential(ResolvedStakeCredential credential) {
        byte[] selected = reverseCredentialPrefix(credential);
        List<HotHistoryMutation> mutations = new ArrayList<>();
        List<PointerCoordinate> coordinates = new ArrayList<>();
        for (var entry : store.scanDataPrefix(dataset, selected)) {
            coordinates.add(decodeLogicalKey(entry.value()));
            mutations.add(new HotHistoryMutation(entry.value(), null));
            mutations.add(new HotHistoryMutation(entry.logicalKey(), null));
        }
        return new CredentialDeletion(coordinates, mutations);
    }

    public List<HotHistoryMutation> deleteMutations(PointerCoordinate pointer,
                                                    ResolvedStakeCredential credential) {
        return List.of(new HotHistoryMutation(logicalKey(pointer), null),
                new HotHistoryMutation(reverseKey(credential, pointer), null));
    }

    public byte[] logicalKey(PointerCoordinate pointer) {
        return ByteBuffer.allocate(prefix.length + Long.BYTES + Integer.BYTES * 2)
                .put(prefix)
                .putLong(pointer.slot())
                .putInt(pointer.txIndex())
                .putInt(pointer.certIndex())
                .array();
    }

    private PointerCoordinate decodeLogicalKey(byte[] key) {
        if (key.length != prefix.length + Long.BYTES + Integer.BYTES * 2) {
            throw new ArchiveStoreException("invalid pointer resolver key");
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) throw new ArchiveStoreException("pointer resolver namespace mismatch");
        }
        ByteBuffer value = ByteBuffer.wrap(key, prefix.length, key.length - prefix.length);
        return new PointerCoordinate(value.getLong(), value.getInt(), value.getInt());
    }

    private byte[] reverseCredentialPrefix(ResolvedStakeCredential credential) {
        return ByteBuffer.allocate(reversePrefix.length + 1 + credential.hash().length)
                .put(reversePrefix).put(typeByte(credential.type())).put(credential.hash()).array();
    }

    private byte[] reverseKey(ResolvedStakeCredential credential, PointerCoordinate pointer) {
        byte[] selected = reverseCredentialPrefix(credential);
        return ByteBuffer.allocate(selected.length + Long.BYTES + Integer.BYTES * 2)
                .put(selected).putLong(pointer.slot()).putInt(pointer.txIndex()).putInt(pointer.certIndex()).array();
    }

    private static byte[] encode(ResolvedStakeCredential credential) {
        byte[] hash = credential.hash();
        byte type = typeByte(credential.type());
        return ByteBuffer.allocate(1 + hash.length).put(type).put(hash).array();
    }

    private static byte typeByte(String type) {
        return switch (type) {
            case "key" -> 0;
            case "script" -> 1;
            default -> throw new IllegalArgumentException("unsupported stake credential type: " + type);
        };
    }

    private static ResolvedStakeCredential decode(byte[] value) {
        if (value.length != 29 || (value[0] != 0 && value[0] != 1)) {
            throw new ArchiveStoreException("invalid pointer resolver value");
        }
        byte[] hash = java.util.Arrays.copyOfRange(value, 1, value.length);
        return new ResolvedStakeCredential(value[0] == 0 ? "key" : "script", hash);
    }

    public record PointerCoordinate(long slot, int txIndex, int certIndex) {
        public PointerCoordinate {
            if (slot < 0 || txIndex < 0 || certIndex < 0) throw new IllegalArgumentException("negative pointer");
        }
    }

    public record CredentialDeletion(List<PointerCoordinate> coordinates,
                                     List<HotHistoryMutation> mutations) {
        public CredentialDeletion {
            coordinates = List.copyOf(coordinates);
            mutations = List.copyOf(mutations);
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
