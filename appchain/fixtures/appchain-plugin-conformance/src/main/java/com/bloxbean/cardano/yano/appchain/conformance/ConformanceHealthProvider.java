package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthReport;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded health fixture used by packaged JVM and native plugin smokes. */
public final class ConformanceHealthProvider implements PluginHealthProvider {
    public static final String ID = NativePluginConformanceVerifier.BUNDLE_ID;
    public static final String CHECK_ID = "fixture";
    static final String MODE_PROPERTY = "yano.plugin.conformance.health-mode";
    static final String HANG_MARKER_PROPERTY =
            "yano.plugin.conformance.health-hang-marker";

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
        HealthMode mode = configuredMode();
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
                return snapshotFor(mode);
            }

            @Override
            public void close() {
                ConformanceTcclProbe.productCallback(firstCallback, "health close");
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return source;
    }

    static HealthMode configuredMode() {
        String configured = System.getProperty(MODE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return HealthMode.UP;
        }
        try {
            return HealthMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            // Do not echo operator-controlled values into diagnostics or labels.
            throw new IllegalArgumentException("unsupported conformance health mode");
        }
    }

    static PluginHealthSnapshot snapshotFor(HealthMode mode) {
        if (mode == HealthMode.HANG) {
            return hangForever();
        }
        return new PluginHealthSnapshot(List.of(
                new PluginHealthReport(CHECK_ID, mode.status)));
    }

    private static PluginHealthSnapshot hangForever() {
        signalHangEntered();
        while (true) {
            try {
                Thread.sleep(TimeUnit.DAYS.toMillis(1));
            } catch (InterruptedException ignored) {
                // Deliberately model a trusted plugin that does not cooperate
                // with bounded sampling or shutdown interruption.
            }
        }
    }

    private static void signalHangEntered() {
        String marker = System.getProperty(HANG_MARKER_PROPERTY);
        if (marker == null || marker.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(marker), "entered\n");
        } catch (IOException | RuntimeException ignored) {
            // The marker is a test-only observation seam; it never changes health behavior.
        }
    }

    enum HealthMode {
        UP(PluginHealthStatus.UP),
        DEGRADED(PluginHealthStatus.DEGRADED),
        DOWN(PluginHealthStatus.DOWN),
        HANG(null);

        private final PluginHealthStatus status;

        HealthMode(PluginHealthStatus status) {
            this.status = status;
        }
    }
}
