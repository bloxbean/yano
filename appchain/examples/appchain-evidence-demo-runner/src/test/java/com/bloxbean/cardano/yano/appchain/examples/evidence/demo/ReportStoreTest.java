package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportStoreTest {
    @TempDir
    Path temporary;

    @Test
    void writesStableCredentialFreeLatestAndVersionedReports() throws Exception {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        Path versioned = store.write(report("scenario-1", "PASS", null));

        assertThat(versioned).exists();
        byte[] latest = store.readLatest();
        JsonNode json = StrictJson.parse(latest);
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.path("chain").path("membersVerified").asInt()).isEqualTo(3);
        assertThat(json.path("chain").path("finalityThreshold").asInt()).isEqualTo(2);
        assertThat(json.path("anchor").path("portableTransactionHashes")).hasSize(1);
        assertThat(json.path("anchor").path(
                "portableTransactionsVisibleOnAllMembers").asBoolean()).isTrue();
        assertThat(json.path("anchor").path(
                "memberObservedTransactionVisibleOnAllMembers").asBoolean()).isTrue();
        assertThat(json.path("checks").get(1).path("status").asText())
                .isEqualTo("NOT_EVALUATED");
        assertThat(new String(latest, StandardCharsets.UTF_8))
                .doesNotContain("api-key", "access-key", "secret-key", "credential");
    }

    @Test
    void rejectsOversizedReportAndSymlinkLatestTarget() throws Exception {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        String huge = "x".repeat(ReportStore.MAX_REPORT_BYTES);
        ScenarioReport oversized = new ScenarioReport(1, "scenario-oversized", "evidence-1",
                "PASS", Instant.EPOCH.toString(), Instant.EPOCH.plusSeconds(1).toString(),
                null, null, null, null,
                List.of(new ScenarioReport.Check(huge, "PASS")), null);
        assertThatThrownBy(() -> store.write(oversized))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.REPORT_WRITE_FAILED);

        Path directory = temporary.resolve("reports");
        Files.createDirectories(directory);
        Path target = temporary.resolve("outside.json");
        Files.writeString(target, "outside");
        try {
            Files.createSymbolicLink(directory.resolve(ReportStore.LATEST_FILE), target);
            assertThatThrownBy(() -> store.write(report("scenario-2", "PASS", null)))
                    .isInstanceOf(DemoException.class);
            assertThat(Files.readString(target)).isEqualTo("outside");
        } catch (UnsupportedOperationException ignored) {
            // Symlinks may be unavailable on some hosts.
        }
    }

    @Test
    void concurrentAtomicLatestWritesAlwaysLeaveOneCompleteDocument() throws Exception {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        int count = 24;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int number = index;
                writes.add(executor.submit(() -> {
                    start.await();
                    store.write(report("scenario-" + number, "PASS", null));
                    return null;
                }));
            }
            start.countDown();
            for (var write : writes) {
                write.get();
            }
        }
        JsonNode latest = StrictJson.parse(store.readLatest());
        assertThat(latest.path("outcome").asText()).isEqualTo("PASS");
        assertThat(latest.path("scenarioId").asText()).startsWith("scenario-");
        try (var paths = Files.list(temporary.resolve("reports"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith("report-"))
                    .count()).isEqualTo(count);
        }
    }

    static ScenarioReport report(String id, String outcome, String failure) {
        return new ScenarioReport(1, id, "evidence-1", outcome,
                Instant.EPOCH.toString(), Instant.EPOCH.plusSeconds(1).toString(),
                new ScenarioReport.ChainSummary("evidence-chain", 12, "ab".repeat(32),
                        "READY", 3, 2, 6, 3, 2),
                new ScenarioReport.StorageSummary("cd".repeat(32), 10,
                        "ef".repeat(32), "bafk-test", true, true),
                new ScenarioReport.KafkaSummary("evidence.available.v1", 0, 4, true),
                new ScenarioReport.AnchorSummary(true, true, List.of("02".repeat(32)),
                        true, true, true, 12, "01".repeat(32), true, true),
                List.of(new ScenarioReport.Check("STATE", "PASS"),
                        new ScenarioReport.Check("BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED")),
                failure);
    }
}
