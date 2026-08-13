package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Archive-private pointer-address resolver. Pointer registrations are replayed
 * sequentially and journaled with the dataset rows, so rollback never depends
 * on the authoritative account-state store being ahead of the archive worker.
 */
public final class SequentialPointerResolver {
    private final RocksDbHotHistoryStore store;
    private final ArchiveDatasetId dataset;
    private final byte[] prefix;

    public SequentialPointerResolver(RocksDbHotHistoryStore store, ArchiveDatasetId dataset, String namespace) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        if (namespace == null || !namespace.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("pointer resolver namespace");
        }
        this.prefix = ("pointer/" + namespace + "/").getBytes(StandardCharsets.UTF_8);
    }

    public Optional<ResolvedStakeCredential> resolve(PointerCoordinate pointer) {
        return store.get(dataset, logicalKey(pointer)).map(SequentialPointerResolver::decode);
    }

    public HotHistoryMutation putMutation(PointerCoordinate pointer, ResolvedStakeCredential credential) {
        return new HotHistoryMutation(logicalKey(pointer), encode(credential));
    }

    public byte[] logicalKey(PointerCoordinate pointer) {
        return ByteBuffer.allocate(prefix.length + Long.BYTES + Integer.BYTES * 2)
                .put(prefix)
                .putLong(pointer.slot())
                .putInt(pointer.txIndex())
                .putInt(pointer.certIndex())
                .array();
    }

    private static byte[] encode(ResolvedStakeCredential credential) {
        byte[] hash = credential.hash();
        byte type = switch (credential.type()) {
            case "key" -> 0;
            case "script" -> 1;
            default -> throw new IllegalArgumentException("unsupported stake credential type: " + credential.type());
        };
        return ByteBuffer.allocate(1 + hash.length).put(type).put(hash).array();
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

    public record ResolvedStakeCredential(String type, byte[] hash) {
        public ResolvedStakeCredential {
            type = Objects.requireNonNull(type, "type");
            hash = Objects.requireNonNull(hash, "hash").clone();
            if (hash.length != 28) throw new IllegalArgumentException("stake credential must be 28 bytes");
        }

        @Override public byte[] hash() { return hash.clone(); }
    }
}
