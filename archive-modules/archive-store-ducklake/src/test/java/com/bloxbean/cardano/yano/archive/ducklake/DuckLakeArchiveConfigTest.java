package com.bloxbean.cardano.yano.archive.ducklake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DuckLakeArchiveConfigTest {
    @TempDir Path temp;

    @Test
    void defaultFilesFitIncrementalCompactionWithinBoundedBulkMemory() {
        DuckLakeArchiveConfig config = DuckLakeArchiveConfig.defaults(temp);

        assertThat(config.targetFileSizeBytes()).isEqualTo(32L * 1024 * 1024);
        assertThat(config.rowGroupSize()).isEqualTo(100_000);
        assertThat(config.targetFileSizeBytes())
                .isLessThan(DuckDbManagerConfig.defaults(temp.resolve("tmp"))
                        .bulkCatchUp().memoryLimitBytes());
    }

    @Test
    void providerCarriesOperatorLayoutAndRetentionSettingsToDuckLake() {
        DuckLakeArchiveConfig config = DuckLakeArchiveBackendProvider.archiveConfig(temp, Map.of(
                "target-file-size-bytes", Long.toString(16L * 1024 * 1024),
                "row-group-size", "50000",
                "snapshot-retention-hours", "48",
                "cleanup-grace-hours", "6"));

        assertThat(config.targetFileSizeBytes()).isEqualTo(16L * 1024 * 1024);
        assertThat(config.rowGroupSize()).isEqualTo(50_000);
        assertThat(config.snapshotRetention()).isEqualTo(Duration.ofHours(48));
        assertThat(config.cleanupGrace()).isEqualTo(Duration.ofHours(6));
    }
}
