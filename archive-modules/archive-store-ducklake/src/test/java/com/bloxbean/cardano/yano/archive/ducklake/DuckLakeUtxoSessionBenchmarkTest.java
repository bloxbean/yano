package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveColumn;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 0: measures a real {@code UTXO_HISTORY} write session spanning
 * all five tables with production-shaped rows, and sweeps target-table size to
 * test whether {@code verifyLogicalKeys} — which joins staging against the full
 * target — prunes by block range or scales with archive size.
 *
 * <p>The earlier {@code chain_transaction} benchmark used a 10-column table and a
 * small target. {@code transaction_outputs} alone has 26 columns, and the mainnet
 * target tables hold tens of millions of rows, so neither row width nor target
 * size was represented.
 */
class DuckLakeUtxoSessionBenchmarkTest {
    @TempDir Path temp;

    /** Mainnet proportions measured over 2.7h: assets 53%, outputs 22%, inputs 20%, rest negligible. */
    private static final Map<String, Integer> MIX = new LinkedHashMap<>(Map.of(
            "transaction_output_assets", 53,
            "transaction_outputs", 22,
            "transaction_inputs", 20,
            "transaction_datums", 3,
            "transaction_redeemers", 2));

    private static Object value(ArchiveColumn column, long unique, UUID job) {
        return switch (column.type()) {
            case BINARY -> {
                byte[] bytes = new byte[column.name().contains("cbor") ? 96 : 32];
                bytes[0] = (byte) unique;
                bytes[1] = (byte) (unique >>> 8);
                bytes[2] = (byte) (unique >>> 16);
                bytes[3] = (byte) (unique >>> 24);
                yield bytes;
            }
            case TEXT -> column.name().equals("address")
                    ? "addr1q" + "x".repeat(90) + unique
                    : "v" + (unique % 7);
            case BOOLEAN -> (unique & 1) == 0;
            case INT32 -> (int) (unique % 1000);
            case INT64 -> unique;
            case DECIMAL_38 -> BigDecimal.valueOf(1_000_000L + unique);
            case UUID -> job;
        };
    }

    /**
     * Builds a row from the schema so column count and order cannot drift, with
     * primary-key columns varied by {@code unique} to keep logical keys distinct.
     */
    private static ArchiveRow row(ArchiveTableSchema table, long unique, long blockNumber, UUID job) {
        List<Object> values = new ArrayList<>(table.columns().size());
        for (ArchiveColumn column : table.columns()) {
            if (column.name().equals("block_number")) values.add(blockNumber);
            else if (column.name().equals("archive_job_id")) values.add(job);
            else if (table.primaryKey().contains(column.name())) values.add(value(column, unique, job));
            else values.add(value(column, unique % 97, job));
        }
        return new ArchiveRow(table.physicalName(), values);
    }

    private static List<ArchiveRow> batch(int rows, long blockFrom, long blockTo, long uniqueBase, UUID job) {
        List<ArchiveRow> out = new ArrayList<>(rows);
        long unique = uniqueBase;
        for (ArchiveTableSchema table : ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).tables()) {
            int share = MIX.getOrDefault(table.physicalName(), 1);
            int count = Math.max(1, rows * share / 100);
            for (int i = 0; i < count; i++) {
                long block = blockFrom + (long) ((double) i / count * (blockTo - blockFrom));
                out.add(row(table, unique++, block, job));
            }
        }
        return out;
    }

    private DuckLakeHistoryArchiveBackend open(Path root) throws Exception {
        Files.createDirectories(root);
        return DuckLakeHistoryArchiveBackend.open(
                new ArchiveIdentity(UUID.randomUUID(), "ducklake", 1, 1, "fixture-genesis"),
                new DuckLakeArchiveConfig(root.resolve("catalog.sqlite"), root.resolve("data"),
                        Duration.ofSeconds(60), 10, 10, 4L * 1024 * 1024, 100_000,
                        Duration.ofHours(168), Duration.ofHours(24)),
                DuckDbManagerConfig.defaults(root.resolve("tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
    }

    private static ArchiveJob job(long from, long to, byte marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.UTXO_HISTORY,
                ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).projectionVersion(),
                new BlockRange(from, to), new ArchiveRangeAnchor(from * 10, hash, to * 10, hash),
                "canonical-block-v1");
    }

    private static long commitBatch(DuckLakeHistoryArchiveBackend backend, ArchiveJob job,
                                    List<ArchiveRow> rows, StringBuilder report) {
        long start = System.nanoTime();
        try (var write = backend.begin(job)) {
            for (ArchiveRow row : rows) write.append(row);
            write.commit();
            if (write instanceof DuckLakeWriteSession session) {
                report.append("     ").append(session.timings().summary()).append('\n');
            }
        }
        return System.nanoTime() - start;
    }

    @Test
    void attributesUtxoHistorySessionAcrossTargetSizes() throws Exception {
        int measuredRows = 60_000;
        System.out.println("=== ADR-038 Phase 0: UTXO_HISTORY 5-table session ("
                + measuredRows + " production-shaped rows/commit) ===");

        // Sweep append implementation and how much data already sits in the target.
        // Order is deliberately appender-first: running legacy first would let JIT
        // warmup flatter the second implementation measured.
        for (String mode : List.of("appender", "legacy")) {
        System.setProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY, mode);
        for (int preload : new int[]{0, 16}) {
            Path root = temp.resolve(mode + preload);
            try (DuckLakeHistoryArchiveBackend backend = open(root)) {
                long unique = 0;
                long block = 1;
                for (int i = 0; i < preload; i++) {
                    ArchiveJob warm = job(block, block + 999, (byte) (i + 1));
                    commitBatch(backend, warm, batch(measuredRows, block, block + 999, unique, warm.jobId()),
                            new StringBuilder());
                    unique += measuredRows + 10;
                    block += 1000;
                }

                StringBuilder report = new StringBuilder();
                ArchiveJob measured = job(block, block + 999, (byte) 99);
                List<ArchiveRow> rows = batch(measuredRows, block, block + 999, unique, measured.jobId());
                long elapsed = commitBatch(backend, measured, rows, report);

                System.out.printf("-- [%s] target preloaded with %d batches (~%d rows)%n",
                        mode, preload, preload * measuredRows);
                System.out.printf("     wall %.3f s -> %.0f rows/s (%d rows)%n",
                        elapsed / 1e9, rows.size() / (elapsed / 1e9), rows.size());
                System.out.print(report);
                assertThat(rows).isNotEmpty();
            }
        }
        }
        System.clearProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY);
    }
}
