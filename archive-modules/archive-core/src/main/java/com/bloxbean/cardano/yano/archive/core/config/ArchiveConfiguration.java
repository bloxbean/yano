package com.bloxbean.cardano.yano.archive.core.config;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveSafetyWindows;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Fully resolved archive configuration; runtime binding happens outside archive-core. */
public record ArchiveConfiguration(
        boolean enabled,
        Path historyDirectory,
        ArchiveEngine engine,
        ArchiveStartMode defaultStartMode,
        boolean liveEnabled,
        ArchiveWorkerConfig worker,
        ArchiveSafetyWindows safetyWindows,
        Map<ArchiveDatasetId, DatasetArchiveConfig> datasets) {

    public ArchiveConfiguration {
        Objects.requireNonNull(historyDirectory, "historyDirectory");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(defaultStartMode, "defaultStartMode");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(safetyWindows, "safetyWindows");
        EnumMap<ArchiveDatasetId, DatasetArchiveConfig> resolved = new EnumMap<>(ArchiveDatasetId.class);
        resolved.putAll(Objects.requireNonNull(datasets, "datasets"));
        for (ArchiveDatasetId id : ArchiveDatasetId.values()) {
            resolved.putIfAbsent(id, new DatasetArchiveConfig(false, defaultStartMode, 0));
        }
        datasets = Map.copyOf(resolved);
        validateDatasetDependencies(datasets);
    }

    private static void validateDatasetDependencies(Map<ArchiveDatasetId, DatasetArchiveConfig> datasets) {
        DatasetArchiveConfig utxo = datasets.get(ArchiveDatasetId.UTXO_HISTORY);
        DatasetArchiveConfig transactions = datasets.get(ArchiveDatasetId.TRANSACTION);
        if (utxo.enabled() && !transactions.enabled()) {
            throw new IllegalArgumentException("utxo history requires the transaction dataset");
        }
        if (utxo.enabled() && transactions.retentionEpochs() != 0
                && (utxo.retentionEpochs() == 0 || transactions.retentionEpochs() < utxo.retentionEpochs())) {
            throw new IllegalArgumentException("transaction retention must cover UTXO history retention");
        }
    }
}
