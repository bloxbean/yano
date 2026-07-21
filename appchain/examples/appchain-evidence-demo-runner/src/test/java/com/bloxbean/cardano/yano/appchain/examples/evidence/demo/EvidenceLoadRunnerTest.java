package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceLoadRunnerTest {
    @TempDir
    Path temporary;

    @Test
    void boundsConcurrencyContinuesAfterFailureAndWritesAggregateReport() {
        ReportStore reports = new ReportStore(temporary.resolve("reports"));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();
        CountDownLatch firstWave = new CountDownLatch(3);
        EvidenceLoadRunner.PublisherFactory publishers = () -> new EvidenceLoadRunner.Publisher() {
            @Override
            public ScenarioReport publish(ScenarioRequest request) {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                try {
                    firstWave.countDown();
                    if (!firstWave.await(5, TimeUnit.SECONDS)) {
                        throw new DemoException(DemoError.INTERNAL_ERROR);
                    }
                    ids.add(request.evidenceId());
                    if (request.evidenceId().endsWith("000002")) {
                        throw new DemoException(DemoError.STORAGE_FAILED);
                    }
                    Instant now = Instant.parse("2026-07-18T00:00:00Z");
                    return new ScenarioReport(ScenarioReport.SCHEMA_VERSION,
                            request.evidenceId() + "-1", request.evidenceId(), "PUBLISH", 1,
                            true, 4, "PASS", now.toString(), now.toString(),
                            null, null, null, null, List.of(), null);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DemoException(DemoError.INTERNAL_ERROR);
                } finally {
                    active.decrementAndGet();
                }
            }

            @Override
            public void close() {
            }
        };
        EvidenceLoadRunner runner = new EvidenceLoadRunner(publishers, reports,
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC),
                System::nanoTime);

        LoadReport report = runner.run(new LoadRequest(
                6, 3, "load-case", temporary.resolve("sample.json")));

        assertThat(maximum).hasValue(3);
        assertThat(ids).containsExactlyInAnyOrder(
                "load-case-000001", "load-case-000002", "load-case-000003",
                "load-case-000004", "load-case-000005", "load-case-000006");
        assertThat(report.outcome()).isEqualTo("FAIL");
        assertThat(report.succeeded()).isEqualTo(5);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failureCounts()).containsEntry("STORAGE_FAILED", 1);
        assertThat(report.failureSamples()).singleElement().satisfies(sample -> {
            assertThat(sample.index()).isEqualTo(2);
            assertThat(sample.evidenceId()).isEqualTo("load-case-000002");
        });
        JsonNode persisted = StrictJson.parse(reports.readLatestLoad());
        assertThat(persisted.path("requested").asInt()).isEqualTo(6);
        assertThat(persisted.path("latencyMillis").path("p95").asLong()).isNotNegative();
    }

    @Test
    void pipelineOverlapsStagesStopsFailedItemsAndReportsStageThroughput() {
        ReportStore reports = new ReportStore(temporary.resolve("pipeline-reports"));
        CountDownLatch prepareWave = new CountDownLatch(3);
        CountDownLatch secondPrerequisite = new CountDownLatch(1);
        CountDownLatch firstApproval = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        EvidenceLoadRunner.PipelinePublisherFactory pipeline = () ->
                new EvidenceLoadRunner.PipelinePublisher() {
                    @Override
                    public void probe() {
                        probes.incrementAndGet();
                    }

                    @Override
                    public EvidenceScenario.PipelineItem prepare(ScenarioRequest request) {
                        int index = Integer.parseInt(request.evidenceId()
                                .substring(request.evidenceId().length() - 6));
                        if (index <= 3) {
                            prepareWave.countDown();
                            await(prepareWave);
                        }
                        return item(request);
                    }

                    @Override
                    public EvidenceScenario.PipelineItem prerequisites(
                            EvidenceScenario.PipelineItem item) {
                        if (item.request().evidenceId().endsWith("000002")) {
                            secondPrerequisite.countDown();
                            await(firstApproval);
                        }
                        return item.submitted(2);
                    }

                    @Override
                    public EvidenceScenario.PipelineItem approval(
                            EvidenceScenario.PipelineItem item) {
                        if (item.request().evidenceId().endsWith("000001")) {
                            await(secondPrerequisite);
                            firstApproval.countDown();
                        }
                        if (item.request().evidenceId().endsWith("000002")) {
                            throw new DemoException(DemoError.SUBMISSION_FAILED);
                        }
                        return item.submitted(1);
                    }

                    @Override
                    public EvidenceScenario.PipelineItem release(
                            EvidenceScenario.PipelineItem item) {
                        return item.released("ab".repeat(32)).submitted(1);
                    }

                    @Override
                    public EvidenceScenario.PipelineItem effects(
                            EvidenceScenario.PipelineItem item) {
                        return item.submitted(1);
                    }

                    @Override
                    public ScenarioReport verify(EvidenceScenario.PipelineItem item) {
                        Instant now = Instant.parse("2026-07-18T00:00:00Z");
                        return new ScenarioReport(ScenarioReport.SCHEMA_VERSION,
                                item.request().evidenceId() + "-1",
                                item.request().evidenceId(), "VERIFY", 1,
                                false, 0, "PASS", now.toString(), now.toString(),
                                new ScenarioReport.ChainSummary("chain", 1,
                                        "00".repeat(32), "READY", 3, 2, 6, 3, 2),
                                null, null, null, List.of(), null);
                    }

                    @Override
                    public void close() {
                    }
                };
        EvidenceLoadRunner runner = new EvidenceLoadRunner(
                () -> { throw new AssertionError("lifecycle publisher used"); },
                pipeline, reports,
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC),
                System::nanoTime, 8);

        LoadReport report = runner.run(new LoadRequest(6, 3,
                LoadRequest.Mode.PIPELINE, 4, "pipe-case", temporary.resolve("sample.json")));

        assertThat(report.mode()).isEqualTo("pipeline");
        assertThat(probes).hasValue(1);
        assertThat(report.maxInFlight()).isEqualTo(4);
        assertThat(report.maximumObservedInFlight()).isEqualTo(4);
        assertThat(report.evidenceCapacityPerBlock()).isEqualTo(8);
        assertThat(report.finalityGate()).isEqualTo("APP_FINAL");
        assertThat(report.anchorRequired()).isFalse();
        assertThat(report.succeeded()).isEqualTo(5);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.stages().get("prepare").attempted()).isEqualTo(6);
        assertThat(report.stages().get("approval").failed()).isEqualTo(1);
        assertThat(report.stages().get("release").attempted()).isEqualTo(5);
        assertThat(report.stages().get("verify").succeeded()).isEqualTo(5);
        assertThat(report.failureSamples()).singleElement().satisfies(sample ->
                assertThat(sample.stage()).isEqualTo("approval"));
        assertThat(report.throughput().appMessagesSubmitted()).isEqualTo(27);
        assertThat(report.throughput().effectsVerified()).isEqualTo(15);
    }

    private static EvidenceScenario.PipelineItem item(ScenarioRequest request) {
        byte[] hash = bytes(0x11);
        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                "archive", "staging/item.bin", "verified/item.bin",
                DigestAlgorithm.SHA_256, hash, 32,
                "application/octet-stream", null);
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1("local", CanonicalCid.fromText(
                "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi"),
                true, "demo-single");
        SubmitEvidenceCommandV1 command = new SubmitEvidenceCommandV1(
                request.evidenceId(), 1, object.encode(), bytes(0x22),
                ipfs.encode(), bytes(0x33), "primary", "evidence-ready", bytes(0x44));
        return EvidenceScenario.PipelineItem.prepared(request, command, hash, "item.bin");
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new DemoException(DemoError.INTERNAL_ERROR);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }
}
