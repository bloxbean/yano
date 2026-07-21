package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded aggregate result for one load invocation. */
public record LoadReport(int schemaVersion,
                         String loadId,
                         String idPrefix,
                         int requested,
                         int attempted,
                         int succeeded,
                         int failed,
                         int concurrency,
                         String mode,
                         int maxInFlight,
                         int maximumObservedInFlight,
                         int evidenceCapacityPerBlock,
                         String finalityGate,
                         boolean anchorRequired,
                         String outcome,
                         String startedAt,
                         String finishedAt,
                         long durationMillis,
                         double attemptedPerSecond,
                         double successfulPerSecond,
                         Throughput throughput,
                         LatencyMillis latencyMillis,
                         Map<String, StageMetrics> stages,
                         Map<String, Integer> failureCounts,
                         List<FailureSample> failureSamples) {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_FAILURE_SAMPLES = 32;

    public LoadReport {
        if (schemaVersion != SCHEMA_VERSION
                || loadId == null || !loadId.matches("load-[0-9]{1,19}-[0-9a-f]{8}")
                || idPrefix == null || !idPrefix.matches("[a-z][a-z0-9-]{0,55}")
                || requested < 1 || requested > LoadRequest.MAX_COUNT
                || attempted != requested || succeeded < 0 || failed < 0
                || succeeded + failed != attempted
                || concurrency < 1 || concurrency > LoadRequest.MAX_CONCURRENCY
                || concurrency > requested
                || !("lifecycle".equals(mode) || "pipeline".equals(mode))
                || maxInFlight < concurrency || maxInFlight > LoadRequest.MAX_IN_FLIGHT
                || maxInFlight > requested
                || maximumObservedInFlight < 1 || maximumObservedInFlight > maxInFlight
                || evidenceCapacityPerBlock < 1
                || !("APP_FINAL".equals(finalityGate) || "L1_ANCHORED".equals(finalityGate))
                || anchorRequired != "L1_ANCHORED".equals(finalityGate)
                || !("PASS".equals(outcome) || "FAIL".equals(outcome))
                || "PASS".equals(outcome) != (failed == 0)
                || startedAt == null || finishedAt == null
                || Instant.parse(finishedAt).isBefore(Instant.parse(startedAt))
                || durationMillis < 0
                || !Double.isFinite(attemptedPerSecond) || attemptedPerSecond < 0
                || !Double.isFinite(successfulPerSecond) || successfulPerSecond < 0
                || throughput == null || latencyMillis == null || stages == null
                || failureCounts == null
                || failureSamples == null
                || failureSamples.size() > MAX_FAILURE_SAMPLES) {
            throw new IllegalArgumentException("invalid load report");
        }
        stages = Map.copyOf(stages);
        failureCounts = Map.copyOf(failureCounts);
        failureSamples = List.copyOf(failureSamples);
        Set<String> expectedStages = "pipeline".equals(mode)
                ? Set.of("prepare", "prerequisites", "approval", "release", "effects", "verify")
                : Set.of("lifecycle");
        if (!stages.keySet().equals(expectedStages)
                || stages.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("invalid load stage summary");
        }
        int countedFailures = 0;
        for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
            try {
                DemoError.valueOf(entry.getKey());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid load failure code", invalid);
            }
            if (entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("invalid load failure count");
            }
            countedFailures = Math.addExact(countedFailures, entry.getValue());
        }
        if (countedFailures != failed || failed == 0 != failureSamples.isEmpty()) {
            throw new IllegalArgumentException("load failure summary is inconsistent");
        }
    }

    public record Throughput(int appMessagesSubmitted,
                             double appMessagesPerSecond,
                             int releasesVerified,
                             double releasesPerSecond,
                             int effectsVerified,
                             double effectsPerSecond,
                             int evidenceVerified,
                             double evidencePerSecond) {
        public Throughput {
            if (appMessagesSubmitted < 0 || releasesVerified < 0
                    || effectsVerified < 0 || evidenceVerified < 0
                    || !finiteRate(appMessagesPerSecond)
                    || !finiteRate(releasesPerSecond)
                    || !finiteRate(effectsPerSecond)
                    || !finiteRate(evidencePerSecond)) {
                throw new IllegalArgumentException("invalid load throughput");
            }
        }

        private static boolean finiteRate(double value) {
            return Double.isFinite(value) && value >= 0;
        }
    }

    public record StageMetrics(int attempted,
                               int succeeded,
                               int failed,
                               long totalDurationMillis,
                               double completedPerSecond,
                               LatencyMillis latencyMillis) {
        public StageMetrics {
            if (attempted < 0 || succeeded < 0 || failed < 0
                    || succeeded + failed != attempted || totalDurationMillis < 0
                    || !Double.isFinite(completedPerSecond) || completedPerSecond < 0
                    || attempted == 0 != (latencyMillis == null)) {
                throw new IllegalArgumentException("invalid load stage metrics");
            }
        }
    }

    public record LatencyMillis(long minimum,
                                long p50,
                                long p95,
                                long maximum) {
        public LatencyMillis {
            if (minimum < 0 || p50 < minimum || p95 < p50 || maximum < p95) {
                throw new IllegalArgumentException("invalid load latency summary");
            }
        }
    }

    public record FailureSample(int index,
                                String evidenceId,
                                String code,
                                String stage) {
        public FailureSample {
            if (index < 1 || evidenceId == null
                    || !evidenceId.matches("[a-z][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("invalid load failure sample");
            }
            try {
                DemoError.valueOf(code);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid load failure sample code", invalid);
            }
            if (!Set.of("lifecycle", "prepare", "prerequisites", "approval",
                    "release", "effects", "verify").contains(stage)) {
                throw new IllegalArgumentException("invalid load failure sample stage");
            }
        }
    }
}
