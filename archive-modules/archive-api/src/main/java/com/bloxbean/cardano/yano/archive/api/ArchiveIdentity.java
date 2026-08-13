package com.bloxbean.cardano.yano.archive.api;

import java.util.Objects;
import java.util.UUID;

public record ArchiveIdentity(UUID archiveId, String engine, int schemaVersion, int networkMagic,
                              String genesisHash) {
    public ArchiveIdentity {
        Objects.requireNonNull(archiveId, "archiveId");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(genesisHash, "genesisHash");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    }
}
