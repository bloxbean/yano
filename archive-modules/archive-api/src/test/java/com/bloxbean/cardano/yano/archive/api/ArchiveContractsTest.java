package com.bloxbean.cardano.yano.archive.api;

import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveContractsTest {
    @Test
    void everyDatasetHasOneVersionedSchemaAndStablePaginationOrder() {
        assertThat(ArchiveSchemas.all().keySet()).isEqualTo(EnumSet.allOf(ArchiveDatasetId.class));
        ArchiveSchemas.all().forEach((dataset, schema) -> {
            assertThat(schema.dataset()).isEqualTo(dataset);
            assertThat(schema.projectionVersion()).isPositive();
            assertThat(schema.tables()).isNotEmpty();
            assertThat(schema.paginationOrder()).isNotEmpty();
        });
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).projectionVersion()).isEqualTo(2);
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.ACCOUNT_EVENT).projectionVersion()).isEqualTo(2);
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.ADDRESS_TRANSACTION).projectionVersion()).isEqualTo(2);
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).projectionVersion()).isEqualTo(2);
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.REWARD).projectionVersion()).isEqualTo(3);
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.DREP_DISTRIBUTION).projectionVersion()).isEqualTo(2);
    }

    @Test
    void transactionUsesTheSameNonKeywordPhysicalNameForBothBackends() {
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).tables())
                .extracting(table -> table.physicalName())
                .containsExactly("chain_transaction");
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).tables().getFirst().columns())
                .extracting(column -> column.name())
                .containsExactly("tx_hash", "block_hash", "block_number", "slot", "epoch", "block_time",
                        "tx_index", "valid", "fee", "archive_job_id");
    }

    @Test
    void utxoHistoryKeepsOutputAndNativeAssetsNormalized() {
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).tables())
                .extracting(table -> table.physicalName())
                .containsExactly("addresses", "transaction_outputs", "transaction_output_assets",
                        "transaction_inputs", "datums", "scripts");
    }

    @Test
    void deterministicJobIdentityChangesForAnyCanonicalInput() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 7);
        var identity = new ArchiveNetworkIdentity(1, "genesis");
        var anchors = new ArchiveRangeAnchor(100, hash, 200, hash);
        var first = ArchiveJob.deterministic(identity, ArchiveDatasetId.TRANSACTION, 1,
                new BlockRange(10, 20), anchors, "source-1");
        var retry = ArchiveJob.deterministic(identity, ArchiveDatasetId.TRANSACTION, 1,
                new BlockRange(10, 20), anchors, "source-1");
        var otherRange = ArchiveJob.deterministic(identity, ArchiveDatasetId.TRANSACTION, 1,
                new BlockRange(10, 21), anchors, "source-1");

        assertThat(retry.jobId()).isEqualTo(first.jobId());
        assertThat(otherRange.jobId()).isNotEqualTo(first.jobId());
        hash[0] = 9;
        assertThat(first.anchorBlockHash()[0]).isEqualTo((byte) 7);

        var otherNetwork = ArchiveJob.deterministic(new ArchiveNetworkIdentity(2, "genesis"),
                ArchiveDatasetId.TRANSACTION, 1, new BlockRange(10, 20), anchors, "source-1");
        assertThat(otherNetwork.jobId()).isNotEqualTo(first.jobId());
    }

    @Test
    void rowAndAnchorBinaryValuesAreDefensivelyCopied() {
        byte[] value = {1, 2, 3};
        var row = new ArchiveRow("binary_table", java.util.List.of(value));
        value[0] = 9;
        assertThat((byte[]) row.values().getFirst()).containsExactly(1, 2, 3);
        ((byte[]) row.values().getFirst())[1] = 9;
        assertThat((byte[]) row.values().getFirst()).containsExactly(1, 2, 3);
    }

    @Test
    void coverageRejectsOverlapAndNeverTreatsAGapAsEmptyHistory() {
        var coverage = new ArchiveCoverage(ArchiveDatasetId.TRANSACTION, 1, 3,
                java.util.List.of(new BlockRange(0, 9), new BlockRange(20, 29)));
        assertThat(coverage.covers(5)).isTrue();
        assertThat(coverage.covers(15)).isFalse();

        assertThatThrownBy(() -> new ArchiveCoverage(ArchiveDatasetId.TRANSACTION, 1, 3,
                java.util.List.of(new BlockRange(0, 10), new BlockRange(10, 20))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ArchiveCoverage(ArchiveDatasetId.TRANSACTION, 0, 0,
                java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectionVersion");
        assertThatThrownBy(() -> new ArchiveCoverage(ArchiveDatasetId.TRANSACTION, 1, -1,
                java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
    }

    @Test
    void safetyDefaultsToTwiceGenesisKAndHasNoUnsafeOverride() {
        assertThat(ArchiveSafetyWindows.resolve(2160, null, null))
                .isEqualTo(new ArchiveSafetyWindows(2160, 4320, 4320));
        assertThat(ArchiveSafetyWindows.resolve(100, null, null))
                .isEqualTo(new ArchiveSafetyWindows(100, 200, 200));
        assertThatThrownBy(() -> ArchiveSafetyWindows.resolve(2160, 2159L, 4320L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least genesis k");
        assertThatThrownBy(() -> ArchiveSafetyWindows.resolve(2160, 4320L, 4319L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than or equal");
    }
}
