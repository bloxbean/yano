package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 0: attributes real write-session time to {@code begin},
 * {@code append} and {@code commit}.
 *
 * <p>The append-path micro-benchmark shows the production insert shape sustaining
 * roughly 37k rows/s in isolation, while mainnet observes about 417 rows/s inside
 * the write session. This test exists to find where the missing ~99% goes, since
 * Phase 2 only addresses the append component.
 */
class DuckLakeWriteSessionBenchmarkTest {
    @TempDir Path temp;

    private static final int ROWS = 250_000;

    private DuckLakeArchiveConfig config(Path root, long targetFileSize) {
        return new DuckLakeArchiveConfig(root.resolve("catalog.sqlite"), root.resolve("data"),
                Duration.ofSeconds(30), 10, 10, targetFileSize, 100_000,
                Duration.ofHours(168), Duration.ofHours(24));
    }

    private DuckLakeHistoryArchiveBackend open(Path root, long targetFileSize) throws Exception {
        Files.createDirectories(root);
        return DuckLakeHistoryArchiveBackend.open(
                new ArchiveIdentity(UUID.randomUUID(), "ducklake", 1, 1, "fixture-genesis"),
                config(root, targetFileSize),
                DuckDbManagerConfig.defaults(root.resolve("tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
    }

    private static ArchiveJob job(long from, long to) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 3);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.TRANSACTION,
                ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).projectionVersion(),
                new BlockRange(from, to),
                new ArchiveRangeAnchor(from * 10, hash, to * 10, hash), "canonical-block-v1");
    }

    private static List<ArchiveRow> rows(ArchiveJob job, int count) {
        List<ArchiveRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] txHash = new byte[32];
            txHash[0] = (byte) i; txHash[1] = (byte) (i >>> 8); txHash[2] = (byte) (i >>> 16);
            byte[] blockHash = new byte[32];
            blockHash[0] = (byte) (i / 26);
            rows.add(new ArchiveRow("chain_transaction", List.of(txHash, blockHash,
                    (long) (i / 26), i * 3L, 0L, 0L, i % 26, true, 150_000L + i, job.jobId())));
        }
        return rows;
    }

    private static long fileCount(Path dataPath) throws Exception {
        if (!Files.isDirectory(dataPath)) return 0;
        try (Stream<Path> walk = Files.walk(dataPath)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /**
     * Ages the catalog by committing many small jobs, so snapshot and data-file
     * cardinality approach production. A fresh catalog cannot reproduce the
     * mainnet write cost, which is why ADR-038 Phase 0 requires this.
     */
    private void age(DuckLakeHistoryArchiveBackend backend, int commits) {
        for (int i = 0; i < commits; i++) {
            long from = 1_000_000L + i * 10L;
            ArchiveJob job = job(from, from + 9);
            try (var write = backend.begin(job)) {
                for (ArchiveRow row : rows(job, 20)) write.append(row);
                write.commit();
            }
        }
    }

    private void measure(String label, long targetFileSize) throws Exception {
        measure(label, targetFileSize, 0);
    }

    private void measure(String label, long targetFileSize, int agingCommits) throws Exception {
        Path root = temp.resolve(label.replaceAll("[^a-z0-9]", ""));
        try (DuckLakeHistoryArchiveBackend backend = open(root, targetFileSize)) {
            if (agingCommits > 0) {
                long ageStart = System.nanoTime();
                age(backend, agingCommits);
                System.out.printf("   (aged with %d commits in %.1f s)%n",
                        agingCommits, (System.nanoTime() - ageStart) / 1e9);
            }
            ArchiveJob job = job(1, 10_000);
            List<ArchiveRow> rows = rows(job, ROWS);

            long t0 = System.nanoTime();
            var write = backend.begin(job);
            long t1 = System.nanoTime();
            for (ArchiveRow row : rows) write.append(row);
            long t2 = System.nanoTime();
            write.commit();
            long t3 = System.nanoTime();
            write.close();
            long t4 = System.nanoTime();

            double begin = (t1 - t0) / 1e9;
            double append = (t2 - t1) / 1e9;
            double commit = (t3 - t2) / 1e9;
            double close = (t4 - t3) / 1e9;
            double total = (t4 - t0) / 1e9;

            System.out.printf("-- %s (target-file-size=%dMB)%n", label, targetFileSize / (1024 * 1024));
            System.out.printf("   begin  %7.3f s (%4.1f%%)%n", begin, 100 * begin / total);
            System.out.printf("   append %7.3f s (%4.1f%%)%n", append, 100 * append / total);
            System.out.printf("   commit %7.3f s (%4.1f%%)%n", commit, 100 * commit / total);
            System.out.printf("   close  %7.3f s (%4.1f%%)%n", close, 100 * close / total);
            System.out.printf("   TOTAL  %7.3f s -> %.0f rows/s, parquet files=%d%n",
                    total, ROWS / total, fileCount(root.resolve("data")));
        }
    }

    @Test
    void attributesWriteSessionTimeAcrossStages() throws Exception {
        System.out.println("=== ADR-038 Phase 0: write-session stage attribution ("
                + ROWS + " rows, chain_transaction) ===");
        measure("fresh 4MB", 4L * 1024 * 1024);
        measure("larger 128MB", 128L * 1024 * 1024);
        // Catalog-cardinality sweep: does per-commit cost grow with catalog age?
        measure("aged 200 commits", 4L * 1024 * 1024, 200);
        measure("aged 1000 commits", 4L * 1024 * 1024, 1000);
        assertThat(true).isTrue();
    }
}
