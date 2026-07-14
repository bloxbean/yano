package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.PluginContext;

import java.util.Map;
import java.util.Set;

/**
 * Build-only node-plugin lifecycle probe. The packaged smoke configures the
 * other fixture contributions on a real app chain, so this class deliberately
 * does not bypass the catalog-owned provider registry with a raw ServiceLoader.
 * The fixture is never included in normal artifacts.
 */
public final class NativePluginConformanceVerifier implements NodePlugin {
    public static final String BUNDLE_ID =
            "com.bloxbean.cardano.yano.fixture.plugin-conformance";
    public static final String VERSION = "0.1.0-pre9";
    public static final String REPORT_SERVICE = BUNDLE_ID + ".report";
    public static final String SUCCESS_MARKER =
            "ADR-011.2 node-plugin conformance activated through catalog";

    @Override
    public String id() {
        return BUNDLE_ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<PluginCapability> capabilities() {
        return Set.of(PluginCapability.POLICY);
    }

    @Override
    public void init(PluginContext ctx) {
        ctx.registerService(REPORT_SERVICE, Map.of("node-plugin", id()));
        ctx.logger().info(SUCCESS_MARKER);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void close() {
    }
}
