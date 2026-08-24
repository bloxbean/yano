package com.bloxbean.cardano.yano.archive.api;

import java.nio.file.Path;
import java.util.Map;

/** Service-provider boundary used by runtime assembly to select exactly one backend. */
public interface ArchiveBackendProvider {
    String engine();

    ArchiveBackend open(ArchiveIdentity expectedIdentity, Path historyDirectory,
                        Map<String, String> validatedProperties);
}
