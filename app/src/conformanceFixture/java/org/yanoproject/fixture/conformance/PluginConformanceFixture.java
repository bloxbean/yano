package org.yanoproject.fixture.conformance;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginCapability;
import org.yanoproject.api.plugin.PluginContext;

import java.util.Set;

/**
 * Build-only directory plugin for the packaged JVM smoke. Its node plugin registers an inbound N2N
 * extension through the Host API and then logs {@link #SUCCESS_MARKER}; the health and metrics
 * contributions are activated by the catalog. The fixture is never included in normal artifacts.
 */
public final class PluginConformanceFixture implements NodePlugin {
    public static final String BUNDLE_ID = "org.yanoproject.fixture.plugin-conformance";
    public static final String SUCCESS_MARKER =
            "ADR-011.2 node-plugin conformance activated through catalog";
    static final int INBOUND_PROTOCOL_ID = 100;

    @Override
    public String id() {
        return BUNDLE_ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<PluginCapability> capabilities() {
        return Set.of(PluginCapability.POLICY);
    }

    @Override
    public void init(PluginContext ctx) {
        HostApi host = ctx.getService(HostApi.SERVICE_KEY, HostApi.class)
                .orElseThrow(() -> new IllegalStateException("Host API is unavailable"));
        host.n2n().registerInbound(INBOUND_PROTOCOL_ID, () -> new AppMsgSubmissionServerAgent(
                AppMsgSubmissionConfig.builder().chainIds(Set.of(BUNDLE_ID)).build()));
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
