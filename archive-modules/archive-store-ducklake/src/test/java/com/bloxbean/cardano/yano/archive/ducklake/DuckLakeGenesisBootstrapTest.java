package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionGenesisBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Genesis is committed as an explicit bootstrap rather than a block section, because it belongs
 * to no block and block receipts are keyed by first block - a genesis receipt at block 0 would
 * collide with the real block-0 batch.
 *
 * <p>These cover the crash boundaries: the rows and the completion marker commit in ONE
 * transaction, so "durable but unrecorded" is not a reachable state, and replay after a crash is
 * a no-op rather than a second genesis.
 */
class DuckLakeGenesisBootstrapTest {
    @TempDir Path temp;

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture-genesis");
    private static final ProjectionIdentity IDENTITY = new ProjectionIdentity(NETWORK, "ducklake", 1,
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));

    private DuckLakeHistoryArchiveBackend backend;
    private DuckLakeArchiveConfig config;
    private Path root;

    private DuckLakeProjectionSink open(String name) throws Exception {
        root = temp.resolve(name);
        Files.createDirectories(root);
        config = new DuckLakeArchiveConfig(root.resolve("catalog.sqlite"), root.resolve("data"),
                Duration.ofSeconds(30), 10, 10, 16L * 1024 * 1024, 100_000,
                Duration.ofHours(168), Duration.ofHours(24));
        backend = DuckLakeHistoryArchiveBackend.open(
                new ArchiveIdentity(UUID.randomUUID(), "ducklake", 1, 1, "fixture-genesis"),
                config, DuckDbManagerConfig.defaults(root.resolve("tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
        var sink = newSink();
        sink.initialize(IDENTITY);
        return sink;
    }

    private DuckLakeProjectionSink newSink() {
        return new DuckLakeProjectionSink(
                new DuckDbManager(DuckDbManagerConfig.defaults(
                        config.catalogPath().getParent().resolve("tmp-" + UUID.randomUUID())),
                        new PackagedDuckDbExtensionLoader(temp.resolve("extensions"))),
                config);
    }

    /** One genesis output row, shaped like the real transaction_outputs schema. */
    private static ArchiveRow genesisOutput(String txHash, long lovelace) {
        return new ArchiveRow("transaction_outputs", Arrays.asList(
                java.util.HexFormat.of().parseHex(txHash), 0, -1, "genesis_byron",
                "FHnt4NL7yPXuYUxBF33VX5dZMBDAab2kvSNLRzCskvuKNCSDknzrQvKeQhGUw5a",
                null, "byron", null, null, null, null, null, lovelace,
                "none", null, null, null, null, null, false,
                new byte[]{9}, 0L, 0L, 0L, 0L, UUID.nameUUIDFromBytes("genesis".getBytes())));
    }

    private static ProjectionGenesisBatch batch(String identity, String digest, List<ArchiveRow> rows,
                                                long total) {
        return new ProjectionGenesisBatch(IDENTITY, identity, digest, BigInteger.valueOf(total), rows);
    }

    private long count(String table) throws Exception {
        try (var read = (DuckLakeReadSession) backend.openReadSession();
             Statement sql = read.connection().createStatement();
             ResultSet rs = sql.executeQuery("SELECT count(*) FROM history_lake." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void aFreshArchiveHasNoGenesisReceipt() throws Exception {
        try (var sink = open("fresh")) {
            assertThat(sink.genesisReceipt()).isEmpty();
        }
    }

    @Test
    void theRowsAndTheCompletionMarkerCommitTogether() throws Exception {
        try (var sink = open("commit")) {
            var receipt = sink.commitGenesis(batch("id-1", "digest-1",
                    List.of(genesisOutput("aa".repeat(32), 30_000_000_000_000_000L)),
                    30_000_000_000_000_000L));

            assertThat(receipt.rowCount()).isEqualTo(1);
            assertThat(receipt.totalLovelace()).isEqualTo(BigInteger.valueOf(30_000_000_000_000_000L));
            assertThat(count("transaction_outputs")).isEqualTo(1);
            assertThat(sink.genesisReceipt()).isPresent();
            assertThat(sink.genesisReceipt().orElseThrow().identity()).isEqualTo("id-1");
        }
    }

    @Test
    void replayingTheSameBootstrapWritesNoSecondGenesis() throws Exception {
        // The crash boundary that matters: a restart after commit must be a no-op. The marker is
        // in the same transaction as the rows, so a receipt implies the rows.
        try (var sink = open("replay")) {
            var first = sink.commitGenesis(batch("id-1", "digest-1",
                    List.of(genesisOutput("aa".repeat(32), 42)), 42));
            var second = sink.commitGenesis(batch("id-1", "digest-1",
                    List.of(genesisOutput("aa".repeat(32), 42)), 42));

            assertThat(second.identity()).isEqualTo(first.identity());
            assertThat(count("transaction_outputs")).as("no duplicate genesis rows").isEqualTo(1);
        }
    }

    @Test
    void aDifferentDistributionIsRefusedRatherThanAppended() throws Exception {
        // A different network, or an edited genesis file, must not be projected into an archive
        // that already recorded a different genesis.
        try (var sink = open("mismatch")) {
            sink.commitGenesis(batch("id-1", "digest-1", List.of(genesisOutput("aa".repeat(32), 1)), 1));

            assertThatThrownBy(() -> sink.commitGenesis(
                    batch("id-2", "digest-2", List.of(genesisOutput("bb".repeat(32), 2)), 2)))
                    .isInstanceOf(ProjectionSinkException.class)
                    .hasMessageContaining("already recorded genesis");
            assertThat(count("transaction_outputs")).isEqualTo(1);
        }
    }

    @Test
    void anEmptyDistributionStillGetsADurableMarker() throws Exception {
        // Devnets distribute nothing. "Nothing to distribute" and "never bootstrapped" must not
        // look alike, or the coverage gate could never open for them.
        try (var sink = open("empty")) {
            var receipt = sink.commitGenesis(batch("id-empty", "digest-empty", List.of(), 0));

            assertThat(receipt.rowCount()).isZero();
            assertThat(sink.genesisReceipt()).isPresent();
            assertThat(count("transaction_outputs")).isZero();
        }
    }

    @Test
    void theMarkerSurvivesReopeningTheSink() throws Exception {
        // Restart after the bootstrap: the next process must see genesis as complete, which is
        // what stops startup reconciliation from running it a second time.
        try (var sink = open("reopen")) {
            sink.commitGenesis(batch("id-1", "digest-1", List.of(genesisOutput("aa".repeat(32), 7)), 7));
        }
        var reopened = newSink();
        reopened.initialize(IDENTITY);
        try (reopened) {
            assertThat(reopened.genesisReceipt()).isPresent();
            assertThat(reopened.genesisReceipt().orElseThrow().rowCount()).isEqualTo(1);
            assertThat(count("transaction_outputs")).isEqualTo(1);
        }
    }

    @Test
    void aBatchForAnotherProjectionIdentityIsRefused() throws Exception {
        try (var sink = open("foreign")) {
            var foreign = new ProjectionIdentity(new ArchiveNetworkIdentity(2, "other-genesis"),
                    "ducklake", 1, Set.of(ProjectionSectionType.TRANSACTION));

            assertThatThrownBy(() -> sink.commitGenesis(new ProjectionGenesisBatch(
                    foreign, "id-1", "digest-1", BigInteger.ONE, List.of())))
                    .isInstanceOf(ProjectionSinkException.class)
                    .hasMessageContaining("does not match the sink identity");
        }
    }
}
