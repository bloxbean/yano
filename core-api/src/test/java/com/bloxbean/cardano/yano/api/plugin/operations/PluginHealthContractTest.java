package com.bloxbean.cardano.yano.api.plugin.operations;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginHealthContractTest {

    @Test
    void cachedRuntimeCheckRetainsOnlyBoundedDescriptorStatusAndStaleState() {
        PluginHealthCheckRuntimeInfo runtime = new PluginHealthCheckRuntimeInfo(
                "com.example.health",
                new PluginHealthCheckDescriptor("database", "Database"),
                PluginHealthStatus.UNKNOWN, true);

        assertThat(runtime.bundleId()).isEqualTo("com.example.health");
        assertThat(runtime.descriptor().id()).isEqualTo("database");
        assertThat(runtime.status()).isEqualTo(PluginHealthStatus.UNKNOWN);
        assertThat(runtime.stale()).isTrue();
        assertThat(runtime.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("bundleId", "descriptor", "status", "stale");
        assertThatThrownBy(() -> new PluginHealthCheckRuntimeInfo(
                "invalid", runtime.descriptor(), PluginHealthStatus.UP, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contextIsIdentityOnlyAndNeverAcceptsConfiguration() {
        PluginHealthContext context = new PluginHealthContext(
                "com.example.health", null);

        assertThat(context.bundleId()).isEqualTo("com.example.health");
        assertThat(context.bundleConfig()).isEmpty();
        assertThatThrownBy(() -> new PluginHealthContext(
                "com.example.health", Map.of("secret", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void descriptorsEnforceIdentifierAndPrintableDescriptionBounds() {
        assertThat(new PluginHealthCheckDescriptor(
                "external.ready", "External dependency is ready").id())
                .isEqualTo("external.ready");
        assertThat(new PluginHealthCheckDescriptor(
                "a", "x".repeat(256)).description()).hasSize(256);
        assertThat(new PluginHealthCheckDescriptor(
                "a" + "b".repeat(63), "safe").id()).hasSize(64);

        assertThatThrownBy(() -> new PluginHealthCheckDescriptor(
                "Bad Check", "safe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
        assertThatThrownBy(() -> new PluginHealthCheckDescriptor(
                "ready", "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
        assertThatThrownBy(() -> new PluginHealthCheckDescriptor(
                "ready", "unsafe\ntext"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("printable ASCII");
        assertThatThrownBy(() -> new PluginHealthCheckDescriptor(
                "a" + "b".repeat(64), "safe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match");
    }

    @Test
    void snapshotCanonicalizesCopiesAndBoundsReports() {
        List<PluginHealthReport> source = new ArrayList<>(List.of(
                new PluginHealthReport("zeta", PluginHealthStatus.DOWN),
                new PluginHealthReport("alpha", PluginHealthStatus.UP)));

        PluginHealthSnapshot snapshot = new PluginHealthSnapshot(source);
        source.clear();

        assertThat(snapshot.reports()).extracting(PluginHealthReport::checkId)
                .containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> snapshot.reports().add(
                new PluginHealthReport("next", PluginHealthStatus.UP)))
                .isInstanceOf(UnsupportedOperationException.class);

        List<PluginHealthReport> exactLimit = java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> new PluginHealthReport(
                        "check." + index, PluginHealthStatus.UP))
                .toList();
        assertThat(new PluginHealthSnapshot(exactLimit).reports()).hasSize(16);
        assertThatThrownBy(() -> new PluginHealthSnapshot(
                java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> new PluginHealthReport(
                                "check." + index, PluginHealthStatus.UP))
                        .toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
        assertThatThrownBy(() -> new PluginHealthSnapshot(List.of(
                new PluginHealthReport("same", PluginHealthStatus.UP),
                new PluginHealthReport("same", PluginHealthStatus.DOWN))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void descriptorSchemaCanonicalizesAndRejectsDuplicates() {
        PluginHealthCheckDescriptor alpha = new PluginHealthCheckDescriptor(
                "alpha", "Alpha");
        PluginHealthCheckDescriptor zeta = new PluginHealthCheckDescriptor(
                "zeta", "Zeta");

        assertThat(PluginHealthSchema.validateAndOrder(List.of(zeta, alpha)))
                .extracting(PluginHealthCheckDescriptor::id)
                .containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> PluginHealthSchema.validateAndOrder(List.of(
                alpha, new PluginHealthCheckDescriptor("alpha", "Other"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate ids");

        List<PluginHealthCheckDescriptor> exactLimit =
                java.util.stream.IntStream.range(0, 16)
                        .mapToObj(index -> new PluginHealthCheckDescriptor(
                                "check." + index, "Check"))
                        .toList();
        assertThat(PluginHealthSchema.validateAndOrder(exactLimit)).hasSize(16);
        List<PluginHealthCheckDescriptor> overLimit = new ArrayList<>(exactLimit);
        overLimit.add(new PluginHealthCheckDescriptor("check.16", "Check"));
        assertThatThrownBy(() -> PluginHealthSchema.validateAndOrder(overLimit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
    }
}
