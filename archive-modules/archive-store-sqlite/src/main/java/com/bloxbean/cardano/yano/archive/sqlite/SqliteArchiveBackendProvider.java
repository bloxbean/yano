package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveBackendProvider;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class SqliteArchiveBackendProvider implements ArchiveBackendProvider {
    @Override
    public String engine() {
        return "sqlite";
    }

    @Override
    public ArchiveBackend open(ArchiveIdentity identity, Path historyDirectory,
                               Map<String, String> validatedProperties) {
        SqliteArchiveConfig defaults = SqliteArchiveConfig.defaults(historyDirectory);
        String configured = validatedProperties.get("database.path");
        Path database = configured == null || configured.isBlank()
                ? defaults.databasePath() : Path.of(configured);
        return new SqliteHistoryArchiveBackend(identity, new SqliteArchiveConfig(database,
                defaults.acquireTimeout(), defaults.queryTimeout(), defaults.maxReaders(), defaults.durability(),
                ArchiveWaitPolicy.fromProperties(validatedProperties, defaults.waitPolicy())));
    }
}
