package com.bloxbean.cardano.yano.archive.core.config;

import java.util.Objects;

public record DatasetArchiveConfig(boolean enabled, ArchiveStartMode startMode,
                                   long retentionEpochs) {
    public DatasetArchiveConfig {
        Objects.requireNonNull(startMode, "startMode");
        if (retentionEpochs < 0) throw new IllegalArgumentException("retentionEpochs must not be negative");
    }
}
