package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Atomically published, immutable node-local plugin operations snapshot. */
public record PluginOperationsSnapshot(
        String catalogFingerprint,
        long generation,
        long capturedAtEpochMillis,
        PluginOperationsTotals totals,
        List<PluginBundleRuntimeInfo> bundles,
        List<PluginHealthCheckRuntimeInfo> healthChecks,
        List<PluginMetricSeries> metrics
) {
    public static final int MAX_BUNDLES = 4_096;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public PluginOperationsSnapshot {
        if (catalogFingerprint == null
                || !FINGERPRINT.matcher(catalogFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "catalogFingerprint must be a lowercase SHA-256 value");
        }
        PluginOperationsValidation.nonNegative(generation, "generation");
        PluginOperationsValidation.nonNegative(
                capturedAtEpochMillis, "capturedAtEpochMillis");
        totals = Objects.requireNonNull(totals, "totals");
        bundles = bundles == null ? List.of() : bundles.stream()
                .map(bundle -> Objects.requireNonNull(
                        bundle, "bundles must not contain null"))
                .sorted(Comparator.comparing(PluginBundleRuntimeInfo::id))
                .toList();
        if (bundles.size() > MAX_BUNDLES) {
            throw new IllegalArgumentException("bundles must contain at most 4096 entries");
        }
        Set<String> bundleIds = new HashSet<>();
        Map<String, PluginBundleRuntimeInfo> bundlesById = new HashMap<>();
        Map<String, PluginContributionRuntimeInfo> healthContributionsByBundle =
                new HashMap<>();
        Map<String, PluginContributionRuntimeInfo> metricContributionsByBundle =
                new HashMap<>();
        int selectedBundleCount = 0;
        int activeBundleCount = 0;
        int degradedBundleCount = 0;
        int failedBundleCount = 0;
        int contributionCount = 0;
        int observedContributionCount = 0;
        int observedActiveContributionCount = 0;
        int staleSourceCount = 0;
        long activeCallbackCount = 0;
        for (PluginBundleRuntimeInfo bundle : bundles) {
            if (!bundleIds.add(bundle.id())) {
                throw new IllegalArgumentException("bundles must not contain duplicate ids");
            }
            bundlesById.put(bundle.id(), bundle);
            boolean selected = bundle.lifecycle() != PluginLifecycleState.NOT_SELECTED;
            PluginBundleRuntimeAggregation aggregation =
                    PluginBundleRuntimeAggregation.derive(
                            selected, bundle.contributions());
            aggregation.validate(bundle);
            if (selected) {
                selectedBundleCount++;
            }
            activeCallbackCount += bundle.activeCallbacks();
            if (!selected
                    && (bundle.activeCallbacks() != 0 || bundle.queuedCallbacks() != 0)) {
                throw new IllegalArgumentException(
                        "not-selected bundle must not publish callback activity");
            }
            if (aggregation.lifecycle() == PluginLifecycleState.ACTIVE) {
                activeBundleCount++;
            }
            if (aggregation.health() == PluginHealthStatus.DEGRADED) {
                degradedBundleCount++;
            }
            if (aggregation.hasFailedContribution()) {
                failedBundleCount++;
            }
            contributionCount += aggregation.contributionCount();
            observedContributionCount += aggregation.observedContributionCount();
            observedActiveContributionCount +=
                    aggregation.observedActiveContributionCount();
            staleSourceCount += aggregation.staleSourceCount();
            for (PluginContributionRuntimeInfo contribution : bundle.contributions()) {
                if ("health".equals(contribution.kind())) {
                    PluginContributionRuntimeInfo previous = healthContributionsByBundle.put(
                            bundle.id(), contribution);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "bundle must not publish more than one health contribution");
                    }
                }
                if ("metrics".equals(contribution.kind())) {
                    PluginContributionRuntimeInfo previous = metricContributionsByBundle.put(
                            bundle.id(), contribution);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                            "bundle must not publish more than one metrics contribution");
                    }
                }
            }
        }
        if (totals.discoveredBundles() != bundles.size()
                || totals.selectedBundles() != selectedBundleCount
                || totals.activeBundles() != activeBundleCount
                || totals.degradedBundles() != degradedBundleCount
                || totals.failedBundles() != failedBundleCount
                || totals.contributions() != contributionCount
                || totals.observedContributions() != observedContributionCount
                || totals.observedActiveContributions()
                != observedActiveContributionCount
                || totals.staleSources() != staleSourceCount
                || totals.activeSamples() > activeCallbackCount) {
            throw new IllegalArgumentException(
                    "operations totals do not match the published bundle snapshot");
        }
        healthChecks = healthChecks == null ? List.of() : healthChecks.stream()
                .map(check -> Objects.requireNonNull(
                        check, "healthChecks must not contain null"))
                .sorted(Comparator.comparing(PluginHealthCheckRuntimeInfo::bundleId)
                        .thenComparing(check -> check.descriptor().id()))
                .toList();
        if (healthChecks.size() > PluginHealthCheckDescriptor.MAX_CHECKS_HOST_WIDE) {
            throw new IllegalArgumentException("healthChecks must contain at most 512 entries");
        }
        Set<String> healthCheckIds = new HashSet<>();
        Map<String, Integer> healthCheckCounts = new HashMap<>();
        Map<String, PluginHealthStatus> aggregateHealth = new HashMap<>();
        for (PluginHealthCheckRuntimeInfo check : healthChecks) {
            PluginBundleRuntimeInfo owner = bundlesById.get(check.bundleId());
            if (owner == null) {
                throw new IllegalArgumentException(
                        "health check bundleId is absent from the bundle snapshot");
            }
            if (owner.lifecycle() == PluginLifecycleState.NOT_SELECTED) {
                throw new IllegalArgumentException(
                        "not-selected bundle must not publish health checks");
            }
            PluginContributionRuntimeInfo healthContribution =
                    healthContributionsByBundle.get(check.bundleId());
            if (healthContribution == null) {
                throw new IllegalArgumentException(
                        "health check owner has no health contribution");
            }
            if (!healthContribution.lifecycleObserved()
                    || check.stale() != healthContribution.stale()) {
                throw new IllegalArgumentException(
                        "health check state does not match its health contribution");
            }
            String key = check.bundleId() + '\u0000' + check.descriptor().id();
            if (!healthCheckIds.add(key)) {
                throw new IllegalArgumentException(
                        "healthChecks must not contain duplicate bundle/id pairs");
            }
            int count = healthCheckCounts.merge(check.bundleId(), 1, Integer::sum);
            if (count > PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE) {
                throw new IllegalArgumentException(
                        "healthChecks must contain at most 16 entries per bundle");
            }
            aggregateHealth.merge(check.bundleId(), check.status(),
                    PluginOperationsSnapshot::worseReport);
        }
        for (Map.Entry<String, PluginHealthStatus> aggregate : aggregateHealth.entrySet()) {
            PluginContributionRuntimeInfo healthContribution =
                    healthContributionsByBundle.get(aggregate.getKey());
            if (aggregate.getValue() != healthContribution.health()) {
                throw new IllegalArgumentException(
                        "health check aggregate does not match its health contribution");
            }
        }
        metrics = metrics == null ? List.of() : metrics.stream()
                .map(metric -> Objects.requireNonNull(
                        metric, "metrics must not contain null"))
                .sorted(Comparator.comparing(PluginMetricSeries::bundleId)
                        .thenComparing(metric -> metric.descriptor().id()))
                .toList();
        if (metrics.size() > PluginMetricDescriptor.MAX_SERIES_HOST_WIDE) {
            throw new IllegalArgumentException("metrics must contain at most 4096 entries");
        }
        Set<String> metricIds = new HashSet<>();
        Set<String> metricNames = new HashSet<>();
        Map<String, Integer> metricCounts = new HashMap<>();
        for (PluginMetricSeries metric : metrics) {
            PluginBundleRuntimeInfo owner = bundlesById.get(metric.bundleId());
            if (owner == null) {
                throw new IllegalArgumentException(
                        "metric series bundleId is absent from the bundle snapshot");
            }
            if (owner.lifecycle() == PluginLifecycleState.NOT_SELECTED) {
                throw new IllegalArgumentException(
                        "not-selected bundle must not publish metric series");
            }
            PluginContributionRuntimeInfo metricContribution =
                    metricContributionsByBundle.get(metric.bundleId());
            if (metricContribution == null) {
                throw new IllegalArgumentException(
                        "metric series owner has no metrics contribution");
            }
            if (!metricContribution.lifecycleObserved()
                    || metric.stale() != metricContribution.stale()) {
                throw new IllegalArgumentException(
                        "metric series state does not match its metrics contribution");
            }
            if (!metricIds.add(metric.bundleId() + '\u0000' + metric.descriptor().id())) {
                throw new IllegalArgumentException(
                        "metrics must not contain duplicate bundle/id pairs");
            }
            if (!metricNames.add(metric.bundleId() + '\u0000'
                    + metric.descriptor().name())) {
                throw new IllegalArgumentException(
                        "metrics must not contain duplicate bundle/name pairs");
            }
            int count = metricCounts.merge(metric.bundleId(), 1, Integer::sum);
            if (count > PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE) {
                throw new IllegalArgumentException(
                        "metrics must contain at most 64 entries per bundle");
            }
        }
    }

    /** Source-compatible constructor for snapshots without per-check detail. */
    public PluginOperationsSnapshot(
            String catalogFingerprint,
            long generation,
            long capturedAtEpochMillis,
            PluginOperationsTotals totals,
            List<PluginBundleRuntimeInfo> bundles,
            List<PluginMetricSeries> metrics
    ) {
        this(catalogFingerprint, generation, capturedAtEpochMillis,
                totals, bundles, List.of(), metrics);
    }

    private static PluginHealthStatus worseReport(
            PluginHealthStatus left,
            PluginHealthStatus right
    ) {
        return reportHealthRank(right) > reportHealthRank(left) ? right : left;
    }

    private static int reportHealthRank(PluginHealthStatus status) {
        return switch (status) {
            case UP -> 0;
            case UNKNOWN -> 1;
            case DEGRADED -> 2;
            case DOWN -> 3;
        };
    }
}
