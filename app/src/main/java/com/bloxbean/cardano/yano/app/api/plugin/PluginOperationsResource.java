package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginCatalogView;
import com.bloxbean.cardano.yano.api.plugin.PluginContributionInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginContributionRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailure;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginGaugeValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginInstanceRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSeries;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsTotals;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsView;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginTimerValue;
import com.bloxbean.cardano.yano.app.api.appchain.AppChainAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Host-owned, cache-only ADR-011.4 plugin operations surface. */
@Path("plugin-operations")
@Produces(MediaType.APPLICATION_JSON)
public class PluginOperationsResource {

    static final String DEFAULT_PAGE_SIZE = "50";
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private static final String DNS_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern BUNDLE_ID = Pattern.compile(
            DNS_LABEL + "(?:\\." + DNS_LABEL + ")+" );
    private static final byte[] RESPONSE_TOO_LARGE = (
            "{\"code\":\"RESPONSE_TOO_LARGE\","
                    + "\"error\":\"Plugin operations response exceeds the size limit\"}")
            .getBytes(StandardCharsets.UTF_8);

    @Inject
    PluginCatalogView catalog;

    @Inject
    PluginOperationsView operations;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @AppChainAccess(AppChainAccess.Level.PRIVILEGED)
    public Response summary() {
        try {
            PluginOperationsSnapshot snapshot = operations.snapshot();
            Response mismatch = requireMatchingCatalog(snapshot);
            if (mismatch != null) {
                return mismatch;
            }
            Map<String, Object> body = snapshotEnvelope(snapshot);
            body.put("pluginApiMajor", catalog.pluginApiMajor());
            body.put("pluginApiLevel", catalog.pluginApiLevel());
            body.put("totals", totals(snapshot.totals()));
            body.put("healthCounts", healthCounts(snapshot));
            body.put("metricSeries", snapshot.metrics().size());
            return json(Response.Status.OK.getStatusCode(), body);
        } catch (RuntimeException unavailable) {
            return operationsUnavailable();
        }
    }

    @GET
    @Path("bundles")
    @AppChainAccess(AppChainAccess.Level.PRIVILEGED)
    public Response bundles(@QueryParam("after") String after,
                            @QueryParam("limit") @DefaultValue(DEFAULT_PAGE_SIZE) String limitText) {
        Integer limit = parseLimit(limitText);
        if (limit == null || (after != null && !validBundleId(after))) {
            return invalidRequest("Pagination parameters are invalid");
        }

        try {
            PluginOperationsSnapshot snapshot = operations.snapshot();
            Response mismatch = requireMatchingCatalog(snapshot);
            if (mismatch != null) {
                return mismatch;
            }
            Map<String, PluginBundleRuntimeInfo> runtimeById = runtimeById(snapshot);
            Map<String, Long> metricCounts = snapshot.metrics().stream().collect(
                    Collectors.groupingBy(PluginMetricSeries::bundleId, Collectors.counting()));
            List<PluginBundleInfo> ordered = catalog.bundles().stream()
                    .sorted(Comparator.comparing(PluginBundleInfo::id))
                    .filter(bundle -> after == null || bundle.id().compareTo(after) > 0)
                    .toList();

            int resultSize = Math.min(limit, ordered.size());
            List<Map<String, Object>> items = new ArrayList<>(resultSize);
            for (int i = 0; i < resultSize; i++) {
                PluginBundleInfo bundle = ordered.get(i);
                items.add(bundleSummary(bundle, runtimeById.get(bundle.id()),
                        metricCounts.getOrDefault(bundle.id(), 0L)));
            }

            Map<String, Object> body = snapshotEnvelope(snapshot);
            body.put("items", items);
            body.put("nextAfter", ordered.size() > resultSize && resultSize > 0
                    ? ordered.get(resultSize - 1).id() : null);
            return json(Response.Status.OK.getStatusCode(), body);
        } catch (RuntimeException unavailable) {
            return operationsUnavailable();
        }
    }

    @GET
    @Path("bundles/{bundleId}")
    @AppChainAccess(AppChainAccess.Level.PRIVILEGED)
    public Response bundle(@Encoded @PathParam("bundleId") String bundleId) {
        if (!validBundleId(bundleId)) {
            return notFound();
        }
        try {
            PluginOperationsSnapshot snapshot = operations.snapshot();
            Response mismatch = requireMatchingCatalog(snapshot);
            if (mismatch != null) {
                return mismatch;
            }
            PluginBundleInfo inventory = catalog.bundles().stream()
                    .filter(bundle -> bundle.id().equals(bundleId))
                    .findFirst()
                    .orElse(null);
            if (inventory == null) {
                return notFound();
            }
            PluginBundleRuntimeInfo runtime = runtimeById(snapshot).get(bundleId);
            List<PluginMetricSeries> metrics = snapshot.metrics().stream()
                    .filter(metric -> metric.bundleId().equals(bundleId))
                    .toList();
            List<PluginHealthCheckRuntimeInfo> healthChecks = snapshot.healthChecks().stream()
                    .filter(check -> check.bundleId().equals(bundleId))
                    .toList();
            Map<String, Object> body = snapshotEnvelope(snapshot);
            body.put("bundle", bundleDetail(inventory, runtime, healthChecks, metrics));
            return json(Response.Status.OK.getStatusCode(), body);
        } catch (RuntimeException unavailable) {
            return operationsUnavailable();
        }
    }

    private Response requireMatchingCatalog(PluginOperationsSnapshot snapshot) {
        if (snapshot != null
                && Objects.equals(snapshot.catalogFingerprint(), catalog.fingerprint())) {
            return null;
        }
        return json(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), Map.of(
                "code", "CATALOG_SNAPSHOT_MISMATCH",
                "error", "Plugin operations snapshot is unavailable for the active catalog"));
    }

    private Map<String, Object> snapshotEnvelope(PluginOperationsSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalogFingerprint", snapshot.catalogFingerprint());
        body.put("generation", snapshot.generation());
        body.put("capturedAtEpochMillis", snapshot.capturedAtEpochMillis());
        return body;
    }

    private Map<String, Object> totals(PluginOperationsTotals totals) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discoveredBundles", totals.discoveredBundles());
        result.put("selectedBundles", totals.selectedBundles());
        result.put("activeBundles", totals.activeBundles());
        result.put("degradedBundles", totals.degradedBundles());
        result.put("failedBundles", totals.failedBundles());
        result.put("contributions", totals.contributions());
        result.put("observedContributions", totals.observedContributions());
        result.put("observedActiveContributions", totals.observedActiveContributions());
        result.put("staleSources", totals.staleSources());
        result.put("activeSamples", totals.activeSamples());
        return result;
    }

    private Map<String, Long> healthCounts(PluginOperationsSnapshot snapshot) {
        var selected = catalog.bundles().stream()
                .filter(PluginBundleInfo::selected)
                .map(PluginBundleInfo::id)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Long> result = new LinkedHashMap<>();
        for (PluginHealthStatus status : PluginHealthStatus.values()) {
            long count = snapshot.bundles().stream()
                    .filter(bundle -> selected.contains(bundle.id()))
                    .filter(bundle -> bundle.health() == status)
                    .count();
            result.put(status.name(), count);
        }
        return result;
    }

    private Map<String, Object> bundleSummary(PluginBundleInfo inventory,
                                               PluginBundleRuntimeInfo runtime,
                                               long metricCount) {
        Map<String, Object> body = inventory(inventory);
        addRuntimeSummary(body, runtime);
        body.put("contributionCount", inventory.contributions().size());
        body.put("observedContributionCount", runtime == null ? 0
                : runtime.contributions().stream()
                        .filter(PluginContributionRuntimeInfo::lifecycleObserved)
                        .count());
        body.put("observedActiveContributionCount", runtime == null ? 0
                : runtime.contributions().stream()
                        .filter(PluginContributionRuntimeInfo::lifecycleObserved)
                        .filter(contribution -> contribution.lifecycle()
                                == PluginLifecycleState.ACTIVE)
                        .count());
        body.put("metricCount", metricCount);
        return body;
    }

    private Map<String, Object> bundleDetail(PluginBundleInfo inventory,
                                              PluginBundleRuntimeInfo runtime,
                                              List<PluginHealthCheckRuntimeInfo> healthChecks,
                                              List<PluginMetricSeries> metrics) {
        Map<String, Object> body = inventory(inventory);
        addRuntimeSummary(body, runtime);
        body.put("dependencies", inventory.dependencies());

        Map<String, PluginContributionRuntimeInfo> dynamic = runtime == null
                ? Map.of()
                : runtime.contributions().stream().collect(Collectors.toMap(
                        contribution -> contribution.kind() + '\u0000' + contribution.name(),
                        Function.identity()));
        List<Map<String, Object>> contributions = new ArrayList<>();
        for (PluginContributionInfo contribution : inventory.contributions()) {
            PluginContributionRuntimeInfo current = dynamic.get(
                    contribution.kind() + '\u0000' + contribution.name());
            contributions.add(contribution(contribution, current));
        }
        body.put("contributions", contributions);
        body.put("healthChecks", healthChecks.stream().map(this::healthCheck).toList());
        body.put("metrics", metrics.stream().map(this::metric).toList());
        return body;
    }

    private Map<String, Object> healthCheck(PluginHealthCheckRuntimeInfo check) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", check.descriptor().id());
        body.put("description", check.descriptor().description());
        body.put("status", check.status());
        body.put("stale", check.stale());
        return body;
    }

    private Map<String, Object> inventory(PluginBundleInfo bundle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", bundle.id());
        body.put("version", bundle.version());
        body.put("selected", bundle.selected());
        body.put("selectionStatus", bundle.selectionStatus());
        body.put("legacy", bundle.legacy());
        body.put("source", bundle.source());
        body.put("digest", bundle.digest());
        body.put("digestMode", bundle.digestMode());
        return body;
    }

    private void addRuntimeSummary(Map<String, Object> body,
                                   PluginBundleRuntimeInfo runtime) {
        if (runtime == null) {
            body.put("lifecycle", "UNKNOWN");
            body.put("health", "UNKNOWN");
            body.put("failure", failure(PluginFailure.none()));
            body.put("metricsStale", false);
            body.put("lastTransitionEpochMillis", 0);
            body.put("activeCallbacks", 0);
            body.put("queuedCallbacks", 0);
            body.put("operationCounts", List.of());
            return;
        }
        body.put("lifecycle", runtime.lifecycle());
        body.put("health", runtime.health());
        body.put("failure", failure(runtime.failure()));
        body.put("metricsStale", runtime.metricsStale());
        body.put("lastTransitionEpochMillis", runtime.lastTransitionEpochMillis());
        body.put("activeCallbacks", runtime.activeCallbacks());
        body.put("queuedCallbacks", runtime.queuedCallbacks());
        body.put("operationCounts", runtime.operationCounts().stream().map(count -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("operation", count.operation());
            value.put("outcome", count.outcome());
            value.put("total", count.total());
            return value;
        }).toList());
    }

    private Map<String, Object> contribution(PluginContributionInfo inventory,
                                              PluginContributionRuntimeInfo runtime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", inventory.kind());
        body.put("name", inventory.name());
        body.put("providerClass", inventory.providerClass());
        body.put("trustTier", inventory.trustTier());
        if (runtime == null) {
            body.put("lifecycleObserved", false);
            body.put("lifecycle", "UNKNOWN");
            body.put("health", "UNKNOWN");
            body.put("failure", failure(PluginFailure.none()));
            body.put("stale", false);
            body.put("instances", List.of());
            return body;
        }
        body.put("lifecycleObserved", runtime.lifecycleObserved());
        body.put("lifecycle", runtime.lifecycle());
        body.put("health", runtime.health());
        body.put("failure", failure(runtime.failure()));
        body.put("stale", runtime.stale());
        body.put("instances", runtime.instances().stream().map(this::instance).toList());
        return body;
    }

    private Map<String, Object> instance(PluginInstanceRuntimeInfo instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", instance.scope());
        body.put("lifecycle", instance.lifecycle());
        body.put("health", instance.health());
        body.put("failure", failure(instance.failure()));
        body.put("stale", instance.stale());
        return body;
    }

    private Map<String, Object> failure(PluginFailure failure) {
        return Map.of(
                "code", failure.code(),
                "observedAtEpochMillis", failure.observedAtEpochMillis());
    }

    private Map<String, Object> metric(PluginMetricSeries metric) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", metric.descriptor().id());
        body.put("name", metric.descriptor().name());
        body.put("type", metric.descriptor().type());
        body.put("description", metric.descriptor().description());
        body.put("baseUnit", metric.descriptor().baseUnit());
        body.put("stale", metric.stale());
        if (metric.value() instanceof PluginGaugeValue gauge) {
            body.put("value", gauge.value());
        } else if (metric.value() instanceof PluginCounterValue counter) {
            body.put("total", counter.total());
        } else if (metric.value() instanceof PluginTimerValue timer) {
            body.put("count", timer.count());
            body.put("totalNanos", timer.totalNanos());
        }
        return body;
    }

    private Map<String, PluginBundleRuntimeInfo> runtimeById(
            PluginOperationsSnapshot snapshot
    ) {
        return snapshot.bundles().stream().collect(Collectors.toMap(
                PluginBundleRuntimeInfo::id, Function.identity()));
    }

    private Response json(int status, Object body) {
        final byte[] encoded;
        try {
            encoded = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException encodingFailed) {
            return Response.serverError()
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity("{\"code\":\"ENCODING_FAILED\"}"
                            .getBytes(StandardCharsets.UTF_8))
                    .build();
        }
        if (encoded.length > MAX_RESPONSE_BYTES) {
            return Response.serverError()
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(RESPONSE_TOO_LARGE)
                    .build();
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(encoded)
                .build();
    }

    private Response invalidRequest(String error) {
        return json(Response.Status.BAD_REQUEST.getStatusCode(), Map.of(
                "code", "INVALID_REQUEST", "error", error));
    }

    private Response notFound() {
        return json(Response.Status.NOT_FOUND.getStatusCode(), Map.of(
                "code", "NOT_FOUND", "error", "Plugin bundle was not found"));
    }

    private Response operationsUnavailable() {
        return json(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), Map.of(
                "code", "OPERATIONS_SNAPSHOT_UNAVAILABLE",
                "error", "Plugin operations snapshot is unavailable"));
    }

    private static Integer parseLimit(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,2}")) {
            return null;
        }
        int limit = Integer.parseInt(value);
        return limit <= MAX_PAGE_SIZE ? limit : null;
    }

    public static boolean validBundleId(String value) {
        return value != null && value.length() >= 3 && value.length() <= 160
                && BUNDLE_ID.matcher(value).matches();
    }
}
