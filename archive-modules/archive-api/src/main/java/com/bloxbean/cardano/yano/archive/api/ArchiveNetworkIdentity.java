package com.bloxbean.cardano.yano.archive.api;

import java.util.Objects;

/** Network magic plus genesis fingerprint; magic alone is insufficient for custom networks. */
public record ArchiveNetworkIdentity(int networkMagic, String genesisHash) {
    public ArchiveNetworkIdentity {
        genesisHash = Objects.requireNonNull(genesisHash, "genesisHash").trim().toLowerCase();
        if (genesisHash.isEmpty()) throw new IllegalArgumentException("genesisHash is required");
    }

    public String canonicalForm() {
        return networkMagic + ":" + genesisHash;
    }
}
