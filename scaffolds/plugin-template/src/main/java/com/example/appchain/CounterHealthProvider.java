package com.example.appchain;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthReport;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;

import java.util.List;

/** Example bounded health source for the counter plugin. */
public final class CounterHealthProvider implements PluginHealthProvider {
    public static final String BUNDLE_ID = "com.example.appchain.counter";
    private static final String CHECK_ID = "state-machine";

    @Override
    public String id() {
        return BUNDLE_ID;
    }

    @Override
    public PluginHealthSource create(PluginHealthContext context) {
        if (!BUNDLE_ID.equals(context.bundleId())) {
            throw new IllegalArgumentException("unexpected plugin bundle identity");
        }
        return new PluginHealthSource() {
            @Override
            public List<PluginHealthCheckDescriptor> checks() {
                return List.of(new PluginHealthCheckDescriptor(
                        CHECK_ID, "Counter state machine is available"));
            }

            @Override
            public PluginHealthSnapshot snapshot() {
                return new PluginHealthSnapshot(List.of(
                        new PluginHealthReport(CHECK_ID, PluginHealthStatus.UP)));
            }

            @Override
            public void close() {
            }
        };
    }
}
