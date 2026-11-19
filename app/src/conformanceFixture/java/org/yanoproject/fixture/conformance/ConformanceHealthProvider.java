package org.yanoproject.fixture.conformance;

import org.yanoproject.api.plugin.operations.PluginHealthCheckDescriptor;
import org.yanoproject.api.plugin.operations.PluginHealthContext;
import org.yanoproject.api.plugin.operations.PluginHealthProvider;
import org.yanoproject.api.plugin.operations.PluginHealthReport;
import org.yanoproject.api.plugin.operations.PluginHealthSnapshot;
import org.yanoproject.api.plugin.operations.PluginHealthSource;
import org.yanoproject.api.plugin.operations.PluginHealthStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded health fixture used by the packaged JVM plugin smoke. */
public final class ConformanceHealthProvider implements PluginHealthProvider {
    public static final String ID = PluginConformanceFixture.BUNDLE_ID;
    public static final String CHECK_ID = "fixture";

    @Override
    public String id() {
        ConformanceTcclProbe.requireCatalogFacade("health provider identity");
        return ID;
    }

    @Override
    public PluginHealthSource create(PluginHealthContext context) {
        if (!ID.equals(context.bundleId()) || !context.bundleConfig().isEmpty()) {
            throw new IllegalArgumentException("unexpected conformance health context");
        }
        PluginHealthSource source = new PluginHealthSource() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public List<PluginHealthCheckDescriptor> checks() {
                ConformanceTcclProbe.productCallback(
                        firstCallback, "health descriptor publication");
                return List.of(new PluginHealthCheckDescriptor(
                        CHECK_ID, "Conformance fixture is available"));
            }

            @Override
            public PluginHealthSnapshot snapshot() {
                ConformanceTcclProbe.productCallback(firstCallback, "health sample");
                return new PluginHealthSnapshot(List.of(
                        new PluginHealthReport(CHECK_ID, PluginHealthStatus.UP)));
            }

            @Override
            public void close() {
                ConformanceTcclProbe.productCallback(firstCallback, "health close");
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return source;
    }
}
