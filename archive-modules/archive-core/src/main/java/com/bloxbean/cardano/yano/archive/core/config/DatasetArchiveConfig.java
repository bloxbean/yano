package com.bloxbean.cardano.yano.archive.core.config;

import java.util.Objects;
import java.util.Map;

public record DatasetArchiveConfig(boolean enabled, ArchiveStartMode startMode,
                                   long retentionEpochs, Map<String, Boolean> tables) {
    public DatasetArchiveConfig {
        Objects.requireNonNull(startMode, "startMode");
        if (retentionEpochs < 0) throw new IllegalArgumentException("retentionEpochs must not be negative");
        tables = Map.copyOf(Objects.requireNonNull(tables, "tables"));
    }

    public DatasetArchiveConfig(boolean enabled, ArchiveStartMode startMode, long retentionEpochs) {
        this(enabled, startMode, retentionEpochs, Map.of());
    }

    public boolean tableEnabled(String physicalName) {
        return tables.getOrDefault(physicalName, true);
    }
}
