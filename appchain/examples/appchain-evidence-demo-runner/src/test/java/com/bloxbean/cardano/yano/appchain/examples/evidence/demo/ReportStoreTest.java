package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        assertThat(new String(store.readRecent(), StandardCharsets.UTF_8))
                .startsWith("[")
                .contains("\"scenarioId\":\"scenario-1\"")
                .endsWith("]");
    }

    @Test
    void writesAndReadsASeparateBoundedLoadReport() {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        Instant start = Instant.parse("2026-07-18T00:00:00Z");
        LoadReport report = new LoadReport(2, "load-1784332800000-1234abcd", "load-case",
                4, 4, 4, 0, 2, "lifecycle", 2, 2, 8,
                "APP_FINAL", false, "PASS",
                start.toString(), start.plusSeconds(2).toString(),
                2_000, 2.0, 2.0,
                new LoadReport.Throughput(16, 8, 4, 2, 12, 6, 4, 2),
                new LoadReport.LatencyMillis(400, 450, 600, 600),
                java.util.Map.of("lifecycle", new LoadReport.StageMetrics(
                        4, 4, 0, 2_000, 2.0,
                        new LoadReport.LatencyMillis(400, 450, 600, 600))),
                java.util.Map.of(), List.of());

        Path versioned = store.writeLoad(report);

        assertThat(versioned.getFileName().toString())
                .isEqualTo("load-1784332800000-1234abcd.json");
        JsonNode persisted = StrictJson.parse(store.readLatestLoad());
        assertThat(persisted.path("outcome").asText()).isEqualTo("PASS");
        assertThat(persisted.path("successfulPerSecond").asDouble()).isEqualTo(2.0);
        assertThat(persisted.path("finalityGate").asText()).isEqualTo("APP_FINAL");
        assertThat(persisted.path("anchorRequired").asBoolean()).isFalse();
    }

    @Test
    void catalogsEvidenceAndServesOnlyAnIntegrityCheckedVerifiedJsonCopy() throws Exception {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        byte[] content = "{\n  \"batchId\": \"BATCH-2026-0718\",\n  \"passed\": true\n}\n"
                .getBytes(StandardCharsets.UTF_8);
        ScenarioReport report = verifiedReport("scenario-content", "inspection-product-a",
                2, content, Instant.parse("2026-07-18T05:00:00Z"));
        store.write(report);
        store.writeVerifiedContent(report.evidenceId(), report.businessVersion(),
                content, Digests.sha256(content));

        JsonNode catalog = StrictJson.parse(store.readEvidenceCatalog());
        assertThat(catalog.path("total").asInt()).isEqualTo(1);
        assertThat(catalog.path("items").get(0).path("evidenceId").asText())
                .isEqualTo("inspection-product-a");
        assertThat(catalog.path("items").get(0).path("businessVersion").asLong())
                .isEqualTo(2);
        assertThat(catalog.path("items").get(0).path("contentAvailable").asBoolean())
                .isTrue();

        JsonNode detail = StrictJson.parse(store.readEvidenceDetail(
                "inspection-product-a", 2));
        assertThat(detail.path("report").path("chain").path("businessStatus").asText())
                .isEqualTo("READY");
        assertThat(detail.path("content").path("available").asBoolean()).isTrue();
        assertThat(detail.path("content").path("integrityVerified").asBoolean()).isTrue();
        assertThat(detail.path("content").path("text").asText())
                .isEqualTo(new String(content, StandardCharsets.UTF_8));
        assertThat(detail.path("content").path("objectStoreVerified").asBoolean()).isTrue();
        assertThat(detail.path("content").path("ipfsVerified").asBoolean()).isTrue();

        Path snapshot = temporary.resolve("reports")
                .resolve("content-inspection-product-a-v2.payload");
        Files.writeString(snapshot, "{\"tampered\":true}");
        JsonNode tampered = StrictJson.parse(store.readEvidenceDetail(
                "inspection-product-a", 2));
        assertThat(tampered.path("content").path("available").asBoolean()).isFalse();
        assertThat(tampered.path("content").path("reason").asText())
                .isEqualTo("INTEGRITY_MISMATCH");
    }

    @Test
    void rejectsInvalidVerifiedContentIdentityOrDigest() {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.writeVerifiedContent(
                "../escape", 1, content, Digests.sha256(content)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.writeVerifiedContent(
                "evidence-1", 1, content, new byte[32]))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.REPORT_WRITE_FAILED);
    }

    @Test
    void latestEvidenceOrderingUsesPublicationNotALaterVerificationRun() {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        byte[] firstContent = "{\"product\":\"a\"}".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "{\"product\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        ScenarioReport first = verifiedReport("first-publish", "product-a", 1,
                firstContent, Instant.parse("2026-07-18T01:00:00Z"));
        ScenarioReport second = verifiedReport("second-publish", "product-b", 1,
                secondContent, Instant.parse("2026-07-18T02:00:00Z"));
        ScenarioReport laterVerification = new ScenarioReport(1, "first-verify", "product-a",
                "VERIFY", 1, false, 0, "PASS",
                "2026-07-18T02:59:58Z", "2026-07-18T03:00:00Z",
                first.chain(), first.storage(), first.kafka(), first.anchor(), first.checks(), null);
        store.write(first);
        store.write(second);
        store.write(laterVerification);

        JsonNode items = StrictJson.parse(store.readEvidenceCatalog()).path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("evidenceId").asText()).isEqualTo("product-b");
        assertThat(items.get(1).path("evidenceId").asText()).isEqualTo("product-a");
        assertThat(items.get(1).path("lastVerifiedAt").asText())
                .isEqualTo("2026-07-18T03:00:00Z");
    }

    @Test
    void paginatesAndFiltersTheRetainedEvidenceCatalogOnTheServer() {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        byte[] content = "{\"page\":true}".getBytes(StandardCharsets.UTF_8);
        Instant start = Instant.parse("2026-07-18T04:00:00Z");
        for (int number = 1; number <= 25; number++) {
            String suffix = "%06d".formatted(number);
            store.write(verifiedReport("page-report-" + suffix,
                    "page-item-" + suffix, 1, content, start.plusSeconds(number)));
        }

        JsonNode first = StrictJson.parse(store.readEvidenceCatalog(1, 20, null));
        assertThat(first.path("page").asInt()).isEqualTo(1);
        assertThat(first.path("pageSize").asInt()).isEqualTo(20);
        assertThat(first.path("total").asInt()).isEqualTo(25);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("hasPrevious").asBoolean()).isFalse();
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(first.path("items")).hasSize(20);
        assertThat(first.path("items").get(0).path("evidenceId").asText())
                .isEqualTo("page-item-000025");

        JsonNode second = StrictJson.parse(store.readEvidenceCatalog(2, 20, null));
        assertThat(second.path("page").asInt()).isEqualTo(2);
        assertThat(second.path("hasPrevious").asBoolean()).isTrue();
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(second.path("items")).hasSize(5);
        assertThat(second.path("items").get(4).path("evidenceId").asText())
                .isEqualTo("page-item-000001");

        JsonNode filtered = StrictJson.parse(store.readEvidenceCatalog(
                1, 20, "page-item-000005"));
        assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("evidenceId").asText())
                .isEqualTo("page-item-000005");

        assertThatThrownBy(() -> store.readEvidenceCatalog(0, 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.readEvidenceCatalog(1, 101, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.readEvidenceCatalog(1, 20, "../escape"))
                .isInstanceOf(IllegalArgumentException.class);
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
    void refusesToReadAnOversizedLatestReport() throws Exception {
        Path directory = temporary.resolve("reports");
        Files.createDirectories(directory);
        Path latest = directory.resolve(ReportStore.LATEST_FILE);
        try (var channel = Files.newByteChannel(latest,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(ReportStore.MAX_REPORT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }

        assertThat(new ReportStore(directory).readLatest()).isNull();
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

    @Test
    void writesCredentialFreeRoleAuthorizationWithoutConflatingActorAndRelay() {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        ScenarioReport base = report("role-report", "PASS", null);
        ScenarioReport.AuthorizationSummary authorization =
                new ScenarioReport.AuthorizationSummary(
                        "approval-evidence-1", "evidence-release", 1, "APPROVED",
                        "evidence.release.v1", "11".repeat(32), 200,
                        "22".repeat(32), "manufacturer-a", "manufacturer-org",
                        "manufacturer", List.of(new ScenarioReport.ClauseSummary(
                        "auditors", "auditor", 2, "ORGANIZATION", 2)),
                        List.of(new ScenarioReport.DecisionSummary(
                                "auditor-a", "audit-org-a", "auditor", "auditors",
                                "auditor-key", 20)));
        ScenarioReport role = new ScenarioReport(base.schemaVersion(), base.scenarioId(),
                base.evidenceId(), base.operation(), base.businessVersion(),
                base.authenticatedStateChanged(), base.submittedEnvelopes(), base.outcome(),
                base.startedAt(), base.finishedAt(), base.chain(), base.storage(), base.kafka(),
                base.anchor(), base.checks(), base.failureCode(), authorization);

        store.write(role);

        String json = new String(store.readLatest(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"relayMember\"", "\"proposerActor\"",
                        "\"acceptedCount\" : 2", "\"actor\" : \"auditor-a\"")
                .doesNotContain("seed", "privateKey", "apiKey");
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

    static ScenarioReport verifiedReport(String scenarioId,
                                         String evidenceId,
                                         long version,
                                         byte[] content,
                                         Instant finishedAt) {
        return new ScenarioReport(1, scenarioId, evidenceId,
                version == 1 ? "PUBLISH" : "REPUBLISH", version, true, 3,
                "PASS", finishedAt.minusSeconds(2).toString(), finishedAt.toString(),
                new ScenarioReport.ChainSummary("evidence-chain", 12 + version,
                        "ab".repeat(32), "READY", 3, 2, 6, 3, 2),
                new ScenarioReport.StorageSummary(Digests.hex(Digests.sha256(content)),
                        content.length, "ef".repeat(32), "bafk-test", true, true),
                new ScenarioReport.KafkaSummary("evidence.available.v1", 0, version, true),
                new ScenarioReport.AnchorSummary(true, true, List.of("02".repeat(32)),
                        true, true, true, 12 + version, "01".repeat(32), true, true),
                List.of(new ScenarioReport.Check("STATE", "PASS"),
                        new ScenarioReport.Check(
                                "BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED")),
                null);
    }
}
