package com.bloxbean.cardano.yano.api.plugin.operations;

import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Host-owned derivation of bundle runtime state from its contribution states.
 *
 * <p>The same pure policy is used when the runtime publishes a bundle and when
 * an independently supplied operations snapshot is validated. This keeps the
 * summary lifecycle, health, failure, and counts from drifting from the
 * contribution detail.</p>
 */
public record PluginBundleRuntimeAggregation(
        PluginLifecycleState lifecycle,
        PluginHealthStatus health,
        PluginFailure failure,
        boolean metricsStale,
        boolean hasFailedContribution,
        int contributionCount,
        int observedContributionCount,
        int observedActiveContributionCount,
        int staleSourceCount
) {
    private static final String HEALTH = "health";
    private static final String METRICS = "metrics";

    public PluginBundleRuntimeAggregation {
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        health = Objects.requireNonNull(health, "health");
        failure = Objects.requireNonNull(failure, "failure");
        PluginOperationsValidation.nonNegative(contributionCount, "contributionCount");
        PluginOperationsValidation.nonNegative(
                observedContributionCount, "observedContributionCount");
        PluginOperationsValidation.nonNegative(
                observedActiveContributionCount, "observedActiveContributionCount");
        PluginOperationsValidation.nonNegative(staleSourceCount, "staleSourceCount");
        if (observedContributionCount > contributionCount
                || observedActiveContributionCount > observedContributionCount
                || (!hasFailedContribution && failure.code() != PluginFailureCode.NONE)) {
            throw new IllegalArgumentException("bundle runtime aggregation is inconsistent");
        }
    }

    /** Derive one canonical bundle summary from immutable contribution values. */
    public static PluginBundleRuntimeAggregation derive(
            boolean selected,
            List<PluginContributionRuntimeInfo> contributions
    ) {
        Objects.requireNonNull(contributions, "contributions");
        if (contributions.size() > PluginBundleRuntimeInfo.MAX_CONTRIBUTIONS) {
            throw new IllegalArgumentException(
                    "contributions must contain at most 256 entries");
        }
        List<PluginContributionRuntimeInfo> ordered = contributions.stream()
                .map(contribution -> Objects.requireNonNull(
                        contribution, "contributions must not contain null"))
                .sorted(Comparator.comparing(PluginContributionRuntimeInfo::kind)
                        .thenComparing(PluginContributionRuntimeInfo::name))
                .toList();

        PluginHealthStatus ordinaryHealth = null;
        PluginHealthStatus sourceHealth = null;
        PluginFailure bundleFailure = PluginFailure.none();
        boolean bundleFailureRequired = false;
        boolean requiredFailure = false;
        boolean anyFailure = false;
        boolean anyActive = false;
        boolean anyActivating = false;
        boolean allObserved = true;
        boolean allStopped = true;
        boolean allClosed = true;
        boolean metricsStale = false;
        int observedContributions = 0;
        int observedActiveContributions = 0;
        int staleSources = 0;

        for (PluginContributionRuntimeInfo contribution : ordered) {
            boolean notSelected = contribution.lifecycle()
                    == PluginLifecycleState.NOT_SELECTED;
            if (selected == notSelected) {
                throw new IllegalArgumentException(
                        "bundle and contribution selection state must agree");
            }
            if (notSelected
                    && (contribution.health() != PluginHealthStatus.UNKNOWN
                    || contribution.failure().code() != PluginFailureCode.NONE
                    || contribution.stale()
                    || !contribution.instances().isEmpty())) {
                throw new IllegalArgumentException(
                        "not-selected contribution must not claim dynamic runtime state");
            }
            if (contribution.lifecycle() == PluginLifecycleState.FAILED
                    && contribution.failure().code() == PluginFailureCode.NONE) {
                throw new IllegalArgumentException(
                        "failed contribution must publish a failure code");
            }

            if (contribution.lifecycleObserved()) {
                observedContributions++;
            } else {
                allObserved = false;
            }
            if (contribution.lifecycle() == PluginLifecycleState.ACTIVE) {
                observedActiveContributions++;
                anyActive = true;
            }
            if (contribution.lifecycle() == PluginLifecycleState.ACTIVATING) {
                anyActivating = true;
            }
            if (contribution.lifecycle() != PluginLifecycleState.STOPPED
                    && contribution.lifecycle() != PluginLifecycleState.CLOSED
                    && contribution.lifecycle() != PluginLifecycleState.NOT_SELECTED) {
                allStopped = false;
            }
            if (contribution.lifecycle() != PluginLifecycleState.CLOSED
                    && contribution.lifecycle() != PluginLifecycleState.NOT_SELECTED) {
                allClosed = false;
            }
            if (contribution.lifecycle() == PluginLifecycleState.FAILED) {
                anyFailure = true;
                boolean decisiveFailure = decisive(contribution.trustTier());
                requiredFailure |= decisiveFailure;
                if (bundleFailure.code() == PluginFailureCode.NONE
                        || (decisiveFailure && !bundleFailureRequired)) {
                    bundleFailure = contribution.failure();
                    bundleFailureRequired = decisiveFailure;
                }
            }
            if (contribution.stale()) {
                staleSources++;
            }
            metricsStale |= METRICS.equals(contribution.kind()) && contribution.stale();

            boolean runtimeHealth = contribution.lifecycle() == PluginLifecycleState.ACTIVE
                    || contribution.lifecycle() == PluginLifecycleState.FAILED;
            if (runtimeHealth && HEALTH.equals(contribution.kind())) {
                sourceHealth = contribution.health();
            } else if (runtimeHealth
                    && contribution.health() != PluginHealthStatus.UNKNOWN) {
                ordinaryHealth = ordinaryHealth == null
                        ? contribution.health()
                        : worse(ordinaryHealth, contribution.health());
            }
        }

        PluginHealthStatus bundleHealth = ordinaryHealth != null
                ? ordinaryHealth : PluginHealthStatus.UNKNOWN;
        if (sourceHealth != null) {
            bundleHealth = ordinaryHealth == null
                    ? sourceHealth : worseReport(ordinaryHealth, sourceHealth);
        }
        if (requiredFailure) {
            bundleHealth = PluginHealthStatus.DOWN;
        } else if (anyFailure || staleSources != 0) {
            bundleHealth = worse(bundleHealth, PluginHealthStatus.DEGRADED);
        }

        PluginLifecycleState bundleLifecycle;
        if (!selected) {
            bundleLifecycle = PluginLifecycleState.NOT_SELECTED;
        } else if (requiredFailure
                || (allObserved && anyFailure && !anyActive && !anyActivating)) {
            bundleLifecycle = PluginLifecycleState.FAILED;
        } else if (anyActive) {
            bundleLifecycle = PluginLifecycleState.ACTIVE;
        } else if (anyActivating) {
            bundleLifecycle = PluginLifecycleState.ACTIVATING;
        } else if (!ordered.isEmpty() && allObserved && allClosed) {
            bundleLifecycle = PluginLifecycleState.CLOSED;
        } else if (!ordered.isEmpty() && allObserved && allStopped) {
            bundleLifecycle = PluginLifecycleState.STOPPED;
        } else {
            bundleLifecycle = PluginLifecycleState.VALIDATED;
        }

        return new PluginBundleRuntimeAggregation(
                bundleLifecycle, bundleHealth, bundleFailure, metricsStale,
                anyFailure, ordered.size(), observedContributions,
                observedActiveContributions, staleSources);
    }

    /** Reject a public bundle summary that contradicts its contribution detail. */
    public void validate(PluginBundleRuntimeInfo bundle) {
        Objects.requireNonNull(bundle, "bundle");
        if (bundle.lifecycle() != lifecycle) {
            throw new IllegalArgumentException(
                    "bundle lifecycle does not match its contributions");
        }
        if (bundle.health() != health) {
            throw new IllegalArgumentException(
                    "bundle health does not match its contributions");
        }
        if (!bundle.failure().equals(failure)) {
            throw new IllegalArgumentException(
                    "bundle failure does not match its contributions");
        }
        if (bundle.metricsStale() != metricsStale) {
            throw new IllegalArgumentException(
                    "bundle metrics state does not match its metrics contribution");
        }
    }

    private static boolean decisive(PluginTrustTier trustTier) {
        return trustTier == PluginTrustTier.REQUIRED
                || trustTier == PluginTrustTier.CONSENSUS;
    }

    private static PluginHealthStatus worse(
            PluginHealthStatus left,
            PluginHealthStatus right
    ) {
        return healthRank(right) > healthRank(left) ? right : left;
    }

    private static int healthRank(PluginHealthStatus status) {
        return switch (status) {
            case UNKNOWN -> 0;
            case UP -> 1;
            case DEGRADED -> 2;
            case DOWN -> 3;
        };
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
