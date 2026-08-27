package com.bloxbean.cardano.yano.archive.api;

import java.util.Objects;
import java.util.UUID;

public record ArchiveIdentity(UUID archiveId, String engine, int schemaVersion, int networkMagic,
                              String genesisHash) {
    public ArchiveIdentity {
        Objects.requireNonNull(archiveId, "archiveId");
        engine = Objects.requireNonNull(engine, "engine").trim().toLowerCase();
        genesisHash = Objects.requireNonNull(genesisHash, "genesisHash").trim().toLowerCase();
        if (engine.isEmpty() || genesisHash.isEmpty()) throw new IllegalArgumentException("engine and genesisHash are required");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    }

    public ArchiveNetworkIdentity networkIdentity() {
        return new ArchiveNetworkIdentity(networkMagic, genesisHash);
    }
}
