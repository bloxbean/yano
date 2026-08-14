package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginCatalogView;
import com.bloxbean.cardano.yano.api.plugin.PluginContributionInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSelectionStatus;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginContributionRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailure;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginGaugeValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginInstanceRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSeries;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricType;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperation;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationCount;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationOutcome;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsTotals;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginOperationsResourceTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void summaryReadsOneCachedSnapshotAndStaysBounded() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger reads = new AtomicInteger();
        PluginOperationsResource resource = resource(
                fixture.catalog(), () -> {
                    reads.incrementAndGet();
                    return fixture.snapshot();
                });

        Response response = resource.summary();
        JsonNode body = json(response);

        assertEquals(200, response.getStatus());
        assertEquals(1, reads.get());
        assertEquals(FINGERPRINT, body.path("catalogFingerprint").asText());
        assertEquals(1, body.path("pluginApiMajor").asInt());
        assertEquals(1, body.path("pluginApiLevel").asInt());
        assertEquals(2, body.path("totals").path("discoveredBundles").asInt());
        assertEquals(2, body.path("totals").path("observedContributions").asInt());
        assertEquals(2, body.path("totals")
                .path("observedActiveContributions").asInt());
        assertFalse(body.path("totals").has("activeContributions"));
        assertEquals(1, body.path("healthCounts").path("UP").asInt());
        assertEquals(0, body.path("healthCounts").path("UNKNOWN").asInt(),
                "denied inventory must not affect selected runtime health");
        assertEquals(1, body.path("metricSeries").asInt());
        assertFalse(body.has("bundles"));
    }

    @Test
    void bundlePagesAreDeterministicAndDetailsAreLoadedSeparately() throws Exception {
        Fixture fixture = fixture();
        PluginOperationsResource resource = resource(fixture.catalog(), () -> fixture.snapshot());

        JsonNode first = json(resource.bundles(null, "1"));
        assertEquals("com.example.alpha", first.path("items").get(0).path("id").asText());
        assertEquals("com.example.alpha", first.path("nextAfter").asText());
        assertFalse(first.path("items").get(0).has("contributions"));
        assertFalse(first.path("items").get(0).has("healthChecks"));
        assertEquals(2, first.path("items").get(0).path("contributionCount").asInt());
        assertEquals(2, first.path("items").get(0)
                .path("observedContributionCount").asInt());
        assertEquals(2, first.path("items").get(0)
                .path("observedActiveContributionCount").asInt());

        JsonNode second = json(resource.bundles("com.example.alpha", "1"));
        assertEquals("com.example.zeta", second.path("items").get(0).path("id").asText());
        assertTrue(second.path("nextAfter").isNull());

        JsonNode detail = json(resource.bundle("com.example.alpha")).path("bundle");
        assertEquals("domain-api", detail.path("contributions").get(0).path("kind").asText());
        assertTrue(detail.path("contributions").get(0)
                .path("lifecycleObserved").asBoolean());
        assertEquals("ACTIVE", detail.path("contributions").get(0).path("lifecycle").asText());
        assertEquals("GAUGE", detail.path("metrics").get(0).path("type").asText());
        assertEquals(7.5d, detail.path("metrics").get(0).path("value").asDouble());
        assertFalse(detail.path("metrics").get(0).has("tags"));
    }

    @Test
    void perCheckHealthIsExposedOnlyInProtectedBundleDetail() throws Exception {
        String bundleId = "com.example.health";
        PluginContributionInfo inventoryContribution = new PluginContributionInfo(
                "health", bundleId, "com.example.HealthProvider",
                PluginTrustTier.AUXILIARY_LOCAL);
        PluginCatalogView catalog = catalog(FINGERPRINT, List.of(bundle(
                bundleId, "1.0.0", true, List.of(inventoryContribution))));
        PluginContributionRuntimeInfo contribution = new PluginContributionRuntimeInfo(
                "health", bundleId, PluginTrustTier.AUXILIARY_LOCAL, true,
                PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false,
                List.of(new PluginInstanceRuntimeInfo(
                        "node", PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                        PluginFailure.none(), false)));
        PluginBundleRuntimeInfo runtime = new PluginBundleRuntimeInfo(
                bundleId, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, 2, 0, 0, List.of(),
                List.of(contribution));
        PluginHealthCheckRuntimeInfo check = new PluginHealthCheckRuntimeInfo(
                bundleId,
                new PluginHealthCheckDescriptor("database", "Database connection is usable"),
                PluginHealthStatus.UP, false);
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 4, 1_700_000_000_000L,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(runtime), List.of(check), List.of());
        PluginOperationsResource resource = resource(catalog, () -> snapshot);

        JsonNode summary = json(resource.summary());
        JsonNode page = json(resource.bundles(null, "1"));
        JsonNode detail = json(resource.bundle(bundleId)).path("bundle");

        assertFalse(summary.has("healthChecks"));
        assertFalse(page.path("items").get(0).has("healthChecks"));
        assertEquals(1, detail.path("healthChecks").size());
        assertEquals("database", detail.path("healthChecks").get(0).path("id").asText());
        assertEquals("Database connection is usable",
                detail.path("healthChecks").get(0).path("description").asText());
        assertEquals("UP", detail.path("healthChecks").get(0).path("status").asText());
        assertFalse(detail.path("healthChecks").get(0).path("stale").asBoolean());
    }

    @Test
    void invalidPaginationMissingBundlesAndFingerprintMismatchFailClosed() throws Exception {
        Fixture fixture = fixture();
        PluginOperationsResource resource = resource(fixture.catalog(), () -> fixture.snapshot());

        assertEquals(400, resource.bundles(null, "0").getStatus());
        assertEquals(400, resource.bundles(null, "101").getStatus());
        assertEquals(400, resource.bundles("COM.EXAMPLE.BAD", "1").getStatus());
        assertEquals(404, resource.bundle("com.example.absent").getStatus());

        PluginCatalogView otherCatalog = catalog("sha256:" + "b".repeat(64),
                fixture.catalog().bundles());
        Response mismatch = resource(otherCatalog, () -> fixture.snapshot()).summary();
        assertEquals(503, mismatch.getStatus());
        assertEquals("CATALOG_SNAPSHOT_MISMATCH", json(mismatch).path("code").asText());
    }

    @Test
    void finalEncodedResponseLimitIsEnforced() throws Exception {
        PluginBundleInfo oversized = bundle(
                "com.example.oversized", "v".repeat(PluginOperationsResource.MAX_RESPONSE_BYTES + 1),
                true, List.of());
        PluginCatalogView catalog = catalog(FINGERPRINT, List.of(oversized));
        PluginBundleRuntimeInfo runtime = runtimeBundle(
                "com.example.oversized", PluginLifecycleState.VALIDATED, List.of());
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 2,
                new PluginOperationsTotals(1, 1, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(runtime), List.of());

        Response response = resource(catalog, () -> snapshot).bundles(null, "1");

        assertEquals(500, response.getStatus());
        assertEquals("RESPONSE_TOO_LARGE", json(response).path("code").asText());
    }

    @Test
    void snapshotFailureIsRedactedBehindAStableServiceCode() throws Exception {
        Fixture fixture = fixture();
        String sentinel = "secret-plugin-snapshot-exception";
        PluginOperationsResource resource = resource(fixture.catalog(), () -> {
            throw new IllegalStateException(sentinel);
        });

        Response response = resource.summary();
        JsonNode body = json(response);

        assertEquals(503, response.getStatus());
        assertEquals("OPERATIONS_SNAPSHOT_UNAVAILABLE", body.path("code").asText());
        assertFalse(body.toString().contains(sentinel));
    }

    @Test
    void summaryPublishesDownHealthEvenWithoutLifecycleFailure() throws Exception {
        PluginBundleInfo inventory = bundle(
                "com.example.down", "1.0.0", true, List.of());
        PluginCatalogView catalog = catalog(FINGERPRINT, List.of(inventory));
        PluginContributionRuntimeInfo contribution = new PluginContributionRuntimeInfo(
                "node-plugin", "com.example.down", PluginTrustTier.REQUIRED,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.DOWN,
                PluginFailure.none(), false, List.of());
        PluginBundleRuntimeInfo runtime = new PluginBundleRuntimeInfo(
                "com.example.down", PluginLifecycleState.ACTIVE, PluginHealthStatus.DOWN,
                PluginFailure.none(), false, 1, 0, 0, List.of(),
                List.of(contribution));
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 2,
                new PluginOperationsTotals(1, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(runtime), List.of());

        JsonNode body = json(resource(catalog, () -> snapshot).summary());

        assertEquals(1, body.path("healthCounts").path("DOWN").asInt());
        assertEquals(0, body.path("totals").path("failedBundles").asInt());
    }

    private static PluginOperationsResource resource(
            PluginCatalogView catalog,
            PluginOperationsView operations
    ) {
        PluginOperationsResource resource = new PluginOperationsResource();
        resource.catalog = catalog;
        resource.operations = operations;
        resource.objectMapper = new ObjectMapper();
        return resource;
    }

    private static Fixture fixture() {
        PluginContributionInfo inventoryContribution = new PluginContributionInfo(
                "domain-api", "com.example.alpha", "com.example.AlphaApi",
                PluginTrustTier.PRIVILEGED_LOCAL);
        PluginContributionInfo metricsInventory = new PluginContributionInfo(
                "metrics", "com.example.alpha", "com.example.AlphaMetricsProvider",
                PluginTrustTier.AUXILIARY_LOCAL);
        PluginBundleInfo alpha = bundle(
                "com.example.alpha", "1.0.0", true,
                List.of(inventoryContribution, metricsInventory));
        PluginBundleInfo zeta = bundle("com.example.zeta", "2.0.0", false, List.of());
        PluginCatalogView catalog = catalog(FINGERPRINT, List.of(zeta, alpha));

        PluginContributionRuntimeInfo dynamicContribution = new PluginContributionRuntimeInfo(
                "domain-api", "com.example.alpha", PluginTrustTier.PRIVILEGED_LOCAL,
                true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false,
                List.of(new PluginInstanceRuntimeInfo(
                        "node", PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                        PluginFailure.none(), false)));
        PluginContributionRuntimeInfo metricsContribution =
                new PluginContributionRuntimeInfo(
                        "metrics", "com.example.alpha",
                        PluginTrustTier.AUXILIARY_LOCAL,
                        true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                        PluginFailure.none(), false, List.of());
        PluginBundleRuntimeInfo alphaRuntime = new PluginBundleRuntimeInfo(
                "com.example.alpha", PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, 2, 1, 0,
                List.of(new PluginOperationCount(
                        PluginOperation.DOMAIN_GET, PluginOperationOutcome.SUCCEEDED, 3)),
                List.of(dynamicContribution, metricsContribution));
        PluginBundleRuntimeInfo zetaRuntime = runtimeBundle(
                "com.example.zeta", PluginLifecycleState.NOT_SELECTED, List.of());
        PluginMetricSeries metric = new PluginMetricSeries(
                "com.example.alpha",
                new PluginMetricDescriptor(
                        "queue.depth", "queue.depth", PluginMetricType.GAUGE,
                        "Current queue depth", "items"),
                new PluginGaugeValue(7.5d), false);
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 4, 1_700_000_000_000L,
                new PluginOperationsTotals(2, 1, 1, 0, 0, 2, 2, 2, 0, 0),
                List.of(zetaRuntime, alphaRuntime), List.of(metric));
        return new Fixture(catalog, snapshot);
    }

    private static PluginBundleRuntimeInfo runtimeBundle(
            String id,
            PluginLifecycleState lifecycle,
            List<PluginContributionRuntimeInfo> contributions
    ) {
        return new PluginBundleRuntimeInfo(
                id, lifecycle, PluginHealthStatus.UNKNOWN, PluginFailure.none(),
                false, 0, 0, 0, List.of(), contributions);
    }

    private static PluginBundleInfo bundle(String id,
                                           String version,
                                           boolean selected,
                                           List<PluginContributionInfo> contributions) {
        return new PluginBundleInfo(
                id, version, selected,
                selected ? PluginSelectionStatus.SELECTED : PluginSelectionStatus.DENIED,
                false, PluginSourceCategory.CLASSPATH, "sha256:" + "c".repeat(64),
                PluginDigestMode.JAR, List.of(), contributions);
    }

    private static PluginCatalogView catalog(String fingerprint, List<PluginBundleInfo> bundles) {
        return new PluginCatalogView() {
            @Override
            public int pluginApiMajor() {
                return 1;
            }

            @Override
            public int pluginApiLevel() {
                return 1;
            }

            @Override
            public String fingerprint() {
                return fingerprint;
            }

            @Override
            public List<PluginBundleInfo> bundles() {
                return bundles;
            }

            @Override
            public List<String> selectedBundleOrder() {
                return bundles.stream().filter(PluginBundleInfo::selected)
                        .map(PluginBundleInfo::id).toList();
            }
        };
    }

    private static JsonNode json(Response response) throws Exception {
        byte[] entity = (byte[]) response.getEntity();
        return new ObjectMapper().readTree(entity);
    }

    private record Fixture(PluginCatalogView catalog, PluginOperationsSnapshot snapshot) {
    }
}
