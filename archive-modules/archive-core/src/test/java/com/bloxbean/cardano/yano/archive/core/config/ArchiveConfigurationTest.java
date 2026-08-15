package com.bloxbean.cardano.yano.archive.core.config;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveSafetyWindows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveConfigurationTest {
    @TempDir Path temp;

    @Test
    void defaultsEveryDatasetWithoutSharingLegacyRetention() {
        var config = new ArchiveConfiguration(true, temp, ArchiveEngine.DUCKLAKE,
                ArchiveStartMode.FULL_REQUIRED, ArchiveWorkerConfig.defaults(),
                ArchiveSafetyWindows.resolve(100, null, null), Map.of());

        assertThat(config.datasets()).hasSize(ArchiveDatasetId.values().length);
        assertThat(config.datasets().values()).allMatch(dataset -> !dataset.enabled());
        assertThat(config.datasets().values()).allMatch(dataset -> dataset.retentionEpochs() == 0);
    }

    @Test
    void utxoHistoryRequiresTransactionCoverage() {
        assertThatThrownBy(() -> configuration(Map.of(
                ArchiveDatasetId.UTXO_HISTORY,
                new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction dataset");

        assertThatThrownBy(() -> configuration(Map.of(
                ArchiveDatasetId.UTXO_HISTORY,
                new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0),
                ArchiveDatasetId.TRANSACTION,
                new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 10))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention");
    }

    @Test
    void utxoTableSelectionEnforcesOnlyPhysicalDependencies() {
        var transaction = new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0);
        assertThatThrownBy(() -> configuration(Map.of(
                ArchiveDatasetId.TRANSACTION, transaction,
                ArchiveDatasetId.UTXO_HISTORY, new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0,
                        Map.of("transaction_outputs", false, "transaction_output_assets", true)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires transaction_outputs");

        var datumOnly = configuration(Map.of(
                ArchiveDatasetId.TRANSACTION, transaction,
                ArchiveDatasetId.UTXO_HISTORY, new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0,
                        Map.of("transaction_outputs", false, "transaction_output_assets", false, "transaction_inputs", false,
                                "transaction_datums", true, "transaction_redeemers", false))));
        assertThat(datumOnly.datasets().get(ArchiveDatasetId.UTXO_HISTORY)
                .tableEnabled("transaction_datums")).isTrue();
    }

    @Test
    void mutableDatabaseAndTempPathsMustBeDisjoint() {
        assertThatThrownBy(() -> ArchivePathValidator.requireDisjoint(Map.of(
                "core", temp.resolve("core"),
                "hot", temp.resolve("core/hot"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void automaticProjectionParallelismLeavesCapacityForCoreWork() {
        assertThat(ArchiveWorkerConfig.automaticProjectionParallelism(1, 4)).isEqualTo(1);
        assertThat(ArchiveWorkerConfig.automaticProjectionParallelism(2, 4)).isEqualTo(1);
        assertThat(ArchiveWorkerConfig.automaticProjectionParallelism(4, 4)).isEqualTo(2);
        assertThat(ArchiveWorkerConfig.automaticProjectionParallelism(8, 4)).isEqualTo(4);
        assertThat(ArchiveWorkerConfig.automaticProjectionParallelism(32, 2)).isEqualTo(2);
    }

    private ArchiveConfiguration configuration(Map<ArchiveDatasetId, DatasetArchiveConfig> datasets) {
        return new ArchiveConfiguration(true, temp, ArchiveEngine.DUCKLAKE,
                ArchiveStartMode.TIP, ArchiveWorkerConfig.defaults(),
                ArchiveSafetyWindows.resolve(100, null, null), datasets);
    }
}
