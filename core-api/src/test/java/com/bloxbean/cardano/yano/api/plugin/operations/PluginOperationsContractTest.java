package com.bloxbean.cardano.yano.api.plugin.operations;

import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginOperationsContractTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void failureCarriesOnlyHostCodeAndTimestamp() {
        PluginFailure failure = new PluginFailure(
                PluginFailureCode.CHECK_TIMEOUT, 1);

        assertThat(failure.code()).isEqualTo(PluginFailureCode.CHECK_TIMEOUT);
        assertThat(failure.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("code", "observedAtEpochMillis");
        assertThat(PluginFailure.none()).isEqualTo(
                new PluginFailure(PluginFailureCode.NONE, 0));
        assertThatThrownBy(() -> new PluginFailure(PluginFailureCode.NONE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginFailure(
                PluginFailureCode.CHECK_FAILED, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bundleAndContributionSnapshotsAreCanonicalAndDefensive() {
        List<PluginInstanceRuntimeInfo> instances = new ArrayList<>(List.of(
                instance("chain:zeta"), instance("chain:alpha")));
        PluginContributionRuntimeInfo contribution = new PluginContributionRuntimeInfo(
                "health", "com.example.bundle", PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, instances);
        instances.clear();
        List<PluginContributionRuntimeInfo> contributions = new ArrayList<>(List.of(
                contribution,
                new PluginContributionRuntimeInfo(
                        "app-state-machine", "machine", PluginTrustTier.CONSENSUS,
                        false, PluginLifecycleState.VALIDATED, PluginHealthStatus.UNKNOWN,
                        PluginFailure.none(), false, List.of())));

        PluginBundleRuntimeInfo bundle = new PluginBundleRuntimeInfo(
                "com.example.bundle", PluginLifecycleState.ACTIVE,
                PluginHealthStatus.UP, PluginFailure.none(), false, 1,
                1, 2,
                List.of(new PluginOperationCount(
                        PluginOperation.HEALTH_SAMPLE,
                        PluginOperationOutcome.SUCCEEDED, 3)),
                contributions);
        contributions.clear();

        assertThat(bundle.contributions())
                .extracting(PluginContributionRuntimeInfo::kind)
                .containsExactly("app-state-machine", "health");
        assertThat(contribution.instances())
                .extracting(PluginInstanceRuntimeInfo::scope)
                .containsExactly("chain:alpha", "chain:zeta");
        assertThat(bundle.activeCallbacks()).isOne();
        assertThat(bundle.queuedCallbacks()).isEqualTo(2);
        assertThat(bundle.operationCounts()).containsExactly(new PluginOperationCount(
                PluginOperation.HEALTH_SAMPLE,
                PluginOperationOutcome.SUCCEEDED, 3));
        assertThatThrownBy(() -> bundle.contributions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void operationsSnapshotCanonicalizesAndRejectsDuplicateIdentities() {
        PluginBundleRuntimeInfo zeta = metricBundle("com.example.zeta", false);
        PluginBundleRuntimeInfo alpha = bundle("com.example.alpha");
        PluginMetricDescriptor descriptor = new PluginMetricDescriptor(
                "requests", "requests", PluginMetricType.COUNTER,
                "Requests", "requests");
        List<PluginBundleRuntimeInfo> bundles = new ArrayList<>(List.of(zeta, alpha));
        List<PluginMetricSeries> metrics = new ArrayList<>(List.of(
                new PluginMetricSeries(zeta.id(), descriptor,
                        new PluginCounterValue(1), false)));

        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(2, 2, 2, 0, 0, 2, 2, 2, 0, 0),
                bundles, metrics);
        bundles.clear();
        metrics.clear();

        assertThat(snapshot.bundles()).extracting(PluginBundleRuntimeInfo::id)
                .containsExactly(alpha.id(), zeta.id());
        assertThat(snapshot.metrics()).hasSize(1);
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(2, 2, 2, 0, 0, 0, 0, 0, 0, 0),
                List.of(alpha, alpha), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                "not-a-fingerprint", 0, 0, PluginOperationsTotals.empty(),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");

        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(alpha), List.of(new PluginMetricSeries(
                        zeta.id(), descriptor, new PluginCounterValue(1), false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void operationsSnapshotPublishesCanonicalBoundedPerCheckHealth() {
        String id = "com.example.checks";
        PluginBundleRuntimeInfo owner = healthBundle(
                id, PluginHealthStatus.DOWN, false);
        List<PluginHealthCheckRuntimeInfo> checks = new ArrayList<>(List.of(
                healthCheck(id, "zeta", PluginHealthStatus.DOWN, false),
                healthCheck(id, "alpha", PluginHealthStatus.UP, false)));

        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), checks, List.of());
        checks.clear();

        assertThat(snapshot.healthChecks())
                .extracting(check -> check.descriptor().id())
                .containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> snapshot.healthChecks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), List.of(
                        healthCheck(id, "same", PluginHealthStatus.DOWN, false),
                        healthCheck(id, "same", PluginHealthStatus.DOWN, false)),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), List.of(
                        healthCheck(id, "mismatch", PluginHealthStatus.UP, false)),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), List.of(
                        healthCheck(id, "stale", PluginHealthStatus.DOWN, true)),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        List<PluginHealthCheckRuntimeInfo> tooManyForBundle =
                java.util.stream.IntStream.range(
                                0, PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE + 1)
                        .mapToObj(index -> healthCheck(
                                id, "check." + index,
                                PluginHealthStatus.DOWN, false))
                        .toList();
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), tooManyForBundle, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");

        PluginContributionRuntimeInfo unobservedHealth = new PluginContributionRuntimeInfo(
                "health", id, PluginTrustTier.AUXILIARY_LOCAL,
                false, PluginLifecycleState.VALIDATED, PluginHealthStatus.UNKNOWN,
                PluginFailure.none(), false, List.of());
        PluginBundleRuntimeInfo catalogOnly = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.VALIDATED, PluginHealthStatus.UNKNOWN,
                PluginFailure.none(), false, 0, 0, 0, List.of(),
                List.of(unobservedHealth));
        assertThat(new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 0, 0, 0, 1, 0, 0, 0, 0),
                List.of(catalogOnly), List.of(), List.of()).healthChecks()).isEmpty();

        PluginContributionRuntimeInfo secondHealth = new PluginContributionRuntimeInfo(
                "health", "com.example.other-health", PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.DOWN,
                PluginFailure.none(), false, List.of());
        PluginBundleRuntimeInfo ambiguousOwner = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE, PluginHealthStatus.DOWN,
                PluginFailure.none(), false, 1, 0, 0, List.of(),
                List.of(owner.contributions().getFirst(), secondHealth));
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 2, 2, 2, 0, 0),
                List.of(ambiguousOwner), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one health contribution");
    }

    @Test
    void operationsSnapshotValidatesMetricOwnershipStalenessAndPerBundleLimit() {
        String id = "com.example.metric-owner";
        PluginBundleRuntimeInfo owner = metricBundle(id, false);
        List<PluginMetricSeries> exact = java.util.stream.IntStream.range(
                        0, PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE)
                .mapToObj(index -> metric(id, "metric." + index, false))
                .toList();

        PluginOperationsSnapshot atLimit = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), exact);

        assertThat(atLimit.metrics())
                .hasSize(PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE);
        List<PluginMetricSeries> overLimit = new ArrayList<>(exact);
        overLimit.add(metric(id, "metric.over", false));
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), overLimit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");

        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(bundle(id)), List.of(metric(id, "orphan", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no metrics contribution");

        PluginContributionRuntimeInfo secondMetrics = new PluginContributionRuntimeInfo(
                "metrics", "com.example.other-metrics", PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, List.of());
        PluginBundleRuntimeInfo ambiguousOwner = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, 1, 0, 0, List.of(),
                List.of(owner.contributions().getFirst(), secondMetrics));
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 2, 2, 2, 0, 0),
                List.of(ambiguousOwner), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one metrics contribution");

        PluginContributionRuntimeInfo deniedContribution = metricContribution(
                id, false, PluginLifecycleState.NOT_SELECTED,
                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false);
        PluginBundleRuntimeInfo denied = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.NOT_SELECTED, PluginHealthStatus.UNKNOWN,
                PluginFailure.none(), false, 0, 0, 0, List.of(),
                List.of(deniedContribution));
        assertThat(new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 0, 0, 0, 0, 1, 0, 0, 0, 0),
                List.of(denied), List.of()).metrics()).isEmpty();
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 0, 0, 0, 0, 1, 0, 0, 0, 0),
                List.of(denied), List.of(metric(id, "denied", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-selected");

        PluginContributionRuntimeInfo unobservedContribution = metricContribution(
                id, false, PluginLifecycleState.VALIDATED,
                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false);
        PluginBundleRuntimeInfo unobserved = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.VALIDATED, PluginHealthStatus.UNKNOWN,
                PluginFailure.none(), false, 0, 0, 0, List.of(),
                List.of(unobservedContribution));
        PluginOperationsSnapshot catalogOnly = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 0, 0, 0, 1, 0, 0, 0, 0),
                List.of(unobserved), List.of());
        assertThat(catalogOnly.metrics()).isEmpty();
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 0, 0, 0, 1, 0, 0, 0, 0),
                List.of(unobserved), List.of(metric(id, "unobserved", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(owner), List.of(metric(id, "stale-mismatch", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        PluginBundleRuntimeInfo mismatchedBundleFlag = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), true, 1, 0, 0, List.of(),
                owner.contributions());
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(mismatchedBundleFlag), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundle metrics state");

        PluginBundleRuntimeInfo staleOwner = metricBundle(id, true);
        PluginOperationsSnapshot stale = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 1, 0, 1, 1, 1, 1, 0),
                List.of(staleOwner), List.of(metric(id, "retained", true)));
        assertThat(stale.metrics()).singleElement()
                .extracting(PluginMetricSeries::stale).isEqualTo(true);

        PluginBundleRuntimeInfo staleWithoutContribution = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), true, 1, 0, 0, List.of(),
                bundle(id).contributions());
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(staleWithoutContribution), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundle metrics state");
    }

    @Test
    void observedMetricSourcesMayPublishNoSeriesBeforeFirstGoodOrAfterTermination() {
        String id = "com.example.metric-lifecycle";
        PluginContributionRuntimeInfo beforeFirst = metricContribution(
                id, true, PluginLifecycleState.ACTIVE,
                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false);
        PluginBundleRuntimeInfo active = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE, PluginHealthStatus.UNKNOWN,
                PluginFailure.none(), false, 1, 0, 0, List.of(), List.of(beforeFirst));
        assertThat(new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(active), List.of()).metrics()).isEmpty();

        PluginFailure activationFailure = new PluginFailure(
                PluginFailureCode.ACTIVATION_FAILED, 1);
        PluginContributionRuntimeInfo failedSource = metricContribution(
                id, true, PluginLifecycleState.FAILED,
                PluginHealthStatus.DEGRADED, activationFailure, false);
        PluginBundleRuntimeInfo failed = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.FAILED, PluginHealthStatus.DEGRADED,
                activationFailure, false, 1, 0, 0, List.of(), List.of(failedSource));
        assertThat(new PluginOperationsSnapshot(
                FINGERPRINT, 2, 2,
                new PluginOperationsTotals(1, 1, 0, 1, 1, 1, 1, 0, 0, 0),
                List.of(failed), List.of()).metrics()).isEmpty();

        PluginContributionRuntimeInfo closedSource = metricContribution(
                id, true, PluginLifecycleState.CLOSED,
                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false);
        PluginBundleRuntimeInfo closed = new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.CLOSED, PluginHealthStatus.UNKNOWN,
                PluginFailure.none(), false, 1, 0, 0, List.of(), List.of(closedSource));
        assertThat(new PluginOperationsSnapshot(
                FINGERPRINT, 3, 3,
                new PluginOperationsTotals(1, 1, 0, 0, 0, 1, 1, 0, 0, 0),
                List.of(closed), List.of()).metrics()).isEmpty();
    }

    @Test
    void totalsRejectImpossibleOrNegativeCounts() {
        assertThatThrownBy(() -> new PluginOperationsTotals(
                1, 2, 0, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
        assertThatThrownBy(() -> new PluginOperationsTotals(
                -1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void unobservedContributionsCannotClaimDynamicRuntimeState() {
        assertThatThrownBy(() -> new PluginContributionRuntimeInfo(
                "app-state-machine", "machine", PluginTrustTier.CONSENSUS,
                false, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unobserved");
        assertThatThrownBy(() -> new PluginContributionRuntimeInfo(
                "app-effect-executor", "effect", PluginTrustTier.PRIVILEGED_LOCAL,
                false, PluginLifecycleState.VALIDATED, PluginHealthStatus.UNKNOWN,
                PluginFailure.none(), false,
                List.of(instance("node"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unobserved");
    }

    @Test
    void observedInstancesMustMatchTheirContributionSummaryInApiV1() {
        assertInstanceContradiction(new PluginInstanceRuntimeInfo(
                "node", PluginLifecycleState.STOPPED, PluginHealthStatus.UP,
                PluginFailure.none(), false));
        assertInstanceContradiction(new PluginInstanceRuntimeInfo(
                "node", PluginLifecycleState.ACTIVE, PluginHealthStatus.DOWN,
                PluginFailure.none(), false));
        assertInstanceContradiction(new PluginInstanceRuntimeInfo(
                "node", PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                new PluginFailure(PluginFailureCode.CHECK_FAILED, 1), false));
        assertInstanceContradiction(new PluginInstanceRuntimeInfo(
                "node", PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), true));

        PluginContributionRuntimeInfo withoutInstance = new PluginContributionRuntimeInfo(
                "health", "com.example.instance-free", PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, List.of());
        assertThat(withoutInstance.instances()).isEmpty();
    }

    @Test
    void snapshotValidatesDerivedObservedAndDynamicTotals() {
        PluginContributionRuntimeInfo observed = new PluginContributionRuntimeInfo(
                "health", "com.example.observed", PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, List.of(instance("node")));
        PluginBundleRuntimeInfo bundle = new PluginBundleRuntimeInfo(
                "com.example.observed", PluginLifecycleState.ACTIVE,
                PluginHealthStatus.UP, PluginFailure.none(), false, 1,
                0, 0, List.of(), List.of(observed));

        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        1, 1, 1, 0, 0, 1, 0, 0, 0, 0),
                List.of(bundle), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");
        PluginOperationsSnapshot valid = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(bundle), List.of());
        assertThat(valid.totals().observedContributions()).isOne();
        assertThat(valid.totals().observedActiveContributions()).isOne();

        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        1, 1, 1, 0, 0, 1, 1, 1, 0, 1),
                List.of(bundle), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");

        PluginContributionRuntimeInfo selectedContribution =
                new PluginContributionRuntimeInfo(
                        "app-state-machine", "machine", PluginTrustTier.CONSENSUS,
                        false, PluginLifecycleState.VALIDATED,
                        PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                        false, List.of());
        PluginBundleRuntimeInfo inconsistentSelection = new PluginBundleRuntimeInfo(
                "com.example.unselected", PluginLifecycleState.NOT_SELECTED,
                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false, 0,
                0, 0, List.of(), List.of(selectedContribution));
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        1, 0, 0, 0, 0, 1, 0, 0, 0, 0),
                List.of(inconsistentSelection), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selection state");
    }

    @Test
    void snapshotRejectsBundleSummariesThatContradictContributionState() {
        String id = "com.example.contradiction";
        PluginFailure failure = new PluginFailure(
                PluginFailureCode.ACTIVATION_FAILED, 7);
        PluginContributionRuntimeInfo failedContribution = contribution(
                "domain-api", id, PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.FAILED,
                PluginHealthStatus.DEGRADED, failure, false);
        PluginOperationsTotals totals = new PluginOperationsTotals(
                1, 1, 0, 1, 1, 1, 1, 0, 0, 0);

        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1, totals,
                List.of(runtimeBundle(id, PluginLifecycleState.ACTIVE,
                        PluginHealthStatus.DEGRADED, failure,
                        failedContribution)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundle lifecycle");
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1, totals,
                List.of(runtimeBundle(id, PluginLifecycleState.FAILED,
                        PluginHealthStatus.UP, failure,
                        failedContribution)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundle health");
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1, totals,
                List.of(runtimeBundle(id, PluginLifecycleState.FAILED,
                        PluginHealthStatus.DEGRADED, PluginFailure.none(),
                        failedContribution)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundle failure");

        PluginContributionRuntimeInfo failedWithoutCode = contribution(
                "domain-api", id, PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.FAILED,
                PluginHealthStatus.DEGRADED, PluginFailure.none(), false);
        assertThatThrownBy(() -> PluginBundleRuntimeAggregation.derive(
                true, List.of(failedWithoutCode)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure code");

        PluginContributionRuntimeInfo dynamicNotSelected = contribution(
                "node-plugin", id, PluginTrustTier.REQUIRED,
                true, PluginLifecycleState.NOT_SELECTED,
                PluginHealthStatus.UP, PluginFailure.none(), false);
        assertThatThrownBy(() -> PluginBundleRuntimeAggregation.derive(
                false, List.of(dynamicNotSelected)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-selected contribution");
    }

    @Test
    void bundleAggregationSupportsEveryLifecycleAndRequiredFailurePrecedence() {
        PluginFailure auxiliaryFailure = new PluginFailure(
                PluginFailureCode.CALLBACK_FAILED, 2);
        PluginFailure requiredFailure = new PluginFailure(
                PluginFailureCode.START_FAILED, 3);
        PluginContributionRuntimeInfo auxiliary = contribution(
                "domain-api", "auxiliary", PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.FAILED,
                PluginHealthStatus.DEGRADED, auxiliaryFailure, false);
        PluginContributionRuntimeInfo required = contribution(
                "node-plugin", "required", PluginTrustTier.REQUIRED,
                true, PluginLifecycleState.FAILED,
                PluginHealthStatus.DEGRADED, requiredFailure, false);

        PluginBundleRuntimeAggregation decisive =
                PluginBundleRuntimeAggregation.derive(
                        true, List.of(required, auxiliary));
        assertThat(decisive.lifecycle()).isEqualTo(PluginLifecycleState.FAILED);
        assertThat(decisive.health()).isEqualTo(PluginHealthStatus.DOWN);
        assertThat(decisive.failure()).isEqualTo(requiredFailure);
        assertThat(decisive.hasFailedContribution()).isTrue();
        assertThat(PluginBundleRuntimeAggregation.derive(
                true, List.of(auxiliary, required))).isEqualTo(decisive);

        PluginFailure failed = new PluginFailure(
                PluginFailureCode.ACTIVATION_FAILED, 5);
        List<PluginBundleRuntimeInfo> states = List.of(
                runtimeBundle("com.example.not-selected",
                        PluginLifecycleState.NOT_SELECTED,
                        PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                        contribution("app-state-machine", "not-selected",
                                PluginTrustTier.CONSENSUS, false,
                                PluginLifecycleState.NOT_SELECTED,
                                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false)),
                runtimeBundle("com.example.validated",
                        PluginLifecycleState.VALIDATED,
                        PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                        contribution("app-state-machine", "validated",
                                PluginTrustTier.CONSENSUS, false,
                                PluginLifecycleState.VALIDATED,
                                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false)),
                runtimeBundle("com.example.activating",
                        PluginLifecycleState.ACTIVATING,
                        PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                        contribution("node-plugin", "activating",
                                PluginTrustTier.REQUIRED, true,
                                PluginLifecycleState.ACTIVATING,
                                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false)),
                runtimeBundle("com.example.active",
                        PluginLifecycleState.ACTIVE,
                        PluginHealthStatus.UP, PluginFailure.none(),
                        contribution("node-plugin", "active",
                                PluginTrustTier.REQUIRED, true,
                                PluginLifecycleState.ACTIVE,
                                PluginHealthStatus.UP, PluginFailure.none(), false)),
                runtimeBundle("com.example.stopped",
                        PluginLifecycleState.STOPPED,
                        PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                        contribution("node-plugin", "stopped",
                                PluginTrustTier.REQUIRED, true,
                                PluginLifecycleState.STOPPED,
                                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false)),
                runtimeBundle("com.example.failed",
                        PluginLifecycleState.FAILED,
                        PluginHealthStatus.DEGRADED, failed,
                        contribution("domain-api", "failed",
                                PluginTrustTier.AUXILIARY_LOCAL, true,
                                PluginLifecycleState.FAILED,
                                PluginHealthStatus.DEGRADED, failed, false)),
                runtimeBundle("com.example.closed",
                        PluginLifecycleState.CLOSED,
                        PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                        contribution("node-plugin", "closed",
                                PluginTrustTier.REQUIRED, true,
                                PluginLifecycleState.CLOSED,
                                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false)));

        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(7, 6, 1, 1, 1, 7, 5, 1, 0, 0),
                states, List.of());
        assertThat(snapshot.bundles())
                .extracting(PluginBundleRuntimeInfo::lifecycle)
                .containsExactly(
                        PluginLifecycleState.ACTIVATING,
                        PluginLifecycleState.ACTIVE,
                        PluginLifecycleState.CLOSED,
                        PluginLifecycleState.FAILED,
                        PluginLifecycleState.NOT_SELECTED,
                        PluginLifecycleState.STOPPED,
                        PluginLifecycleState.VALIDATED);
    }

    @Test
    void operationsSnapshotEnforcesExactHostWideBounds() {
        List<PluginBundleRuntimeInfo> exactBundles =
                java.util.stream.IntStream.range(0, PluginOperationsSnapshot.MAX_BUNDLES)
                        .mapToObj(index -> bundle("com.example.bundle" + index))
                        .toList();
        PluginOperationsSnapshot bundleLimit = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        PluginOperationsSnapshot.MAX_BUNDLES,
                        PluginOperationsSnapshot.MAX_BUNDLES,
                        PluginOperationsSnapshot.MAX_BUNDLES,
                        0, 0,
                        PluginOperationsSnapshot.MAX_BUNDLES,
                        PluginOperationsSnapshot.MAX_BUNDLES,
                        PluginOperationsSnapshot.MAX_BUNDLES,
                        0, 0),
                exactBundles, List.of());
        assertThat(bundleLimit.bundles()).hasSize(PluginOperationsSnapshot.MAX_BUNDLES);
        List<PluginBundleRuntimeInfo> tooManyBundles = new ArrayList<>(exactBundles);
        tooManyBundles.add(bundle("com.example.over-limit"));
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1, PluginOperationsTotals.empty(),
                tooManyBundles, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4096");

        int metricBundles = PluginMetricDescriptor.MAX_SERIES_HOST_WIDE
                / PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE;
        List<PluginBundleRuntimeInfo> metricOwners =
                java.util.stream.IntStream.range(0, metricBundles)
                        .mapToObj(index -> metricBundle(
                                "com.example.metrics" + index, false))
                        .toList();
        List<PluginMetricSeries> exactMetrics =
                java.util.stream.IntStream.range(0, metricBundles)
                        .boxed()
                        .flatMap(bundleIndex -> java.util.stream.IntStream.range(
                                        0, PluginMetricDescriptor.MAX_SERIES_PER_BUNDLE)
                                .mapToObj(metricIndex -> metric(
                                        "com.example.metrics" + bundleIndex,
                                        "metric." + metricIndex, false)))
                        .toList();
        PluginOperationsSnapshot metricLimit = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        metricBundles, metricBundles, metricBundles, 0, 0,
                        metricBundles, metricBundles, metricBundles, 0, 0),
                metricOwners, exactMetrics);
        assertThat(metricLimit.metrics())
                .hasSize(PluginMetricDescriptor.MAX_SERIES_HOST_WIDE);
        PluginBundleRuntimeInfo extraMetricOwner = metricBundle(
                "com.example.metricsover", false);
        List<PluginBundleRuntimeInfo> overMetricOwners = new ArrayList<>(metricOwners);
        overMetricOwners.add(extraMetricOwner);
        List<PluginMetricSeries> tooManyMetrics = new ArrayList<>(exactMetrics);
        tooManyMetrics.add(metric(extraMetricOwner.id(), "metric.over", false));
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        metricBundles + 1, metricBundles + 1, metricBundles + 1,
                        0, 0, metricBundles + 1, metricBundles + 1,
                        metricBundles + 1, 0, 0),
                overMetricOwners, tooManyMetrics))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4096");

        int healthBundles = PluginHealthCheckDescriptor.MAX_CHECKS_HOST_WIDE
                / PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE;
        List<PluginBundleRuntimeInfo> exactHealthBundles =
                java.util.stream.IntStream.range(0, healthBundles)
                        .mapToObj(index -> healthBundle(
                                "com.example.health" + index,
                                PluginHealthStatus.UP, false))
                        .toList();
        List<PluginHealthCheckRuntimeInfo> exactHealthChecks =
                java.util.stream.IntStream.range(0, healthBundles)
                        .boxed()
                        .flatMap(bundleIndex -> java.util.stream.IntStream.range(
                                        0, PluginHealthCheckDescriptor.MAX_CHECKS_PER_BUNDLE)
                                .mapToObj(checkIndex -> healthCheck(
                                        "com.example.health" + bundleIndex,
                                        "check." + checkIndex,
                                        PluginHealthStatus.UP, false)))
                        .toList();
        PluginOperationsSnapshot healthLimit = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        healthBundles, healthBundles, healthBundles, 0, 0,
                        healthBundles, healthBundles, healthBundles, 0, 0),
                exactHealthBundles, exactHealthChecks, List.of());
        assertThat(healthLimit.healthChecks())
                .hasSize(PluginHealthCheckDescriptor.MAX_CHECKS_HOST_WIDE);

        List<PluginBundleRuntimeInfo> overHealthBundles =
                new ArrayList<>(exactHealthBundles);
        PluginBundleRuntimeInfo extraHealthBundle = healthBundle(
                "com.example.healthover", PluginHealthStatus.UP, false);
        overHealthBundles.add(extraHealthBundle);
        List<PluginHealthCheckRuntimeInfo> overHealthChecks =
                new ArrayList<>(exactHealthChecks);
        overHealthChecks.add(healthCheck(extraHealthBundle.id(), "extra",
                PluginHealthStatus.UP, false));
        assertThatThrownBy(() -> new PluginOperationsSnapshot(
                FINGERPRINT, 1, 1,
                new PluginOperationsTotals(
                        healthBundles + 1, healthBundles + 1, healthBundles + 1,
                        0, 0, healthBundles + 1, healthBundles + 1,
                        healthBundles + 1, 0, 0),
                overHealthBundles, overHealthChecks, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512");
    }

    private static PluginInstanceRuntimeInfo instance(String scope) {
        return new PluginInstanceRuntimeInfo(
                scope, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false);
    }

    private static void assertInstanceContradiction(PluginInstanceRuntimeInfo instance) {
        assertThatThrownBy(() -> new PluginContributionRuntimeInfo(
                "health", "com.example.instance", PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, List.of(instance)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance runtime state");
    }

    private static PluginBundleRuntimeInfo bundle(String id) {
        PluginContributionRuntimeInfo contribution = new PluginContributionRuntimeInfo(
                "node-plugin", id, PluginTrustTier.REQUIRED,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, List.of());
        return new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, 1,
                0, 0, List.of(), List.of(contribution));
    }

    private static PluginBundleRuntimeInfo runtimeBundle(
            String id,
            PluginLifecycleState lifecycle,
            PluginHealthStatus health,
            PluginFailure failure,
            PluginContributionRuntimeInfo contribution
    ) {
        return new PluginBundleRuntimeInfo(
                id, lifecycle, health, failure, false, 1,
                0, 0, List.of(), List.of(contribution));
    }

    private static PluginContributionRuntimeInfo contribution(
            String kind,
            String name,
            PluginTrustTier trustTier,
            boolean observed,
            PluginLifecycleState lifecycle,
            PluginHealthStatus health,
            PluginFailure failure,
            boolean stale
    ) {
        return new PluginContributionRuntimeInfo(
                kind, name, trustTier, observed, lifecycle, health,
                failure, stale, List.of());
    }

    private static PluginBundleRuntimeInfo healthBundle(
            String id,
            PluginHealthStatus status,
            boolean stale
    ) {
        PluginFailure failure = stale
                ? new PluginFailure(PluginFailureCode.CHECK_FAILED, 1)
                : PluginFailure.none();
        PluginContributionRuntimeInfo health = new PluginContributionRuntimeInfo(
                "health", id, PluginTrustTier.AUXILIARY_LOCAL,
                true, PluginLifecycleState.ACTIVE, status, failure, stale,
                List.of(new PluginInstanceRuntimeInfo(
                        "node", PluginLifecycleState.ACTIVE, status,
                        failure, stale)));
        return new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE, status, PluginFailure.none(),
                false, 1, 0, 0, List.of(), List.of(health));
    }

    private static PluginBundleRuntimeInfo metricBundle(String id, boolean stale) {
        PluginFailure sourceFailure = stale
                ? new PluginFailure(PluginFailureCode.METRICS_FAILED, 1)
                : PluginFailure.none();
        PluginContributionRuntimeInfo metrics = metricContribution(
                id, true, PluginLifecycleState.ACTIVE,
                PluginHealthStatus.UP, sourceFailure, stale);
        return new PluginBundleRuntimeInfo(
                id, PluginLifecycleState.ACTIVE,
                stale ? PluginHealthStatus.DEGRADED : PluginHealthStatus.UP,
                PluginFailure.none(), stale, 1, 0, 0, List.of(), List.of(metrics));
    }

    private static PluginContributionRuntimeInfo metricContribution(
            String id,
            boolean observed,
            PluginLifecycleState lifecycle,
            PluginHealthStatus health,
            PluginFailure failure,
            boolean stale
    ) {
        return new PluginContributionRuntimeInfo(
                "metrics", id, PluginTrustTier.AUXILIARY_LOCAL,
                observed, lifecycle, health, failure, stale, List.of());
    }

    private static PluginMetricSeries metric(String bundleId, String id, boolean stale) {
        return new PluginMetricSeries(
                bundleId,
                new PluginMetricDescriptor(
                        id, id, PluginMetricType.GAUGE, "Metric " + id, ""),
                new PluginGaugeValue(1), stale);
    }

    private static PluginHealthCheckRuntimeInfo healthCheck(
            String bundleId,
            String id,
            PluginHealthStatus status,
            boolean stale
    ) {
        return new PluginHealthCheckRuntimeInfo(
                bundleId, new PluginHealthCheckDescriptor(id, "Check " + id),
                status, stale);
    }
}
