package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record EutxoIndexStoreContext(
        IndexIdentity identity,
        Path dataDirectory,
        Map<String, String> settings
) {
    public EutxoIndexStoreContext {
        identity = Objects.requireNonNull(identity, "identity");
        dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        settings = Map.copyOf(settings);
    }
}
