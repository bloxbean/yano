package org.yanoproject.fixture.conformance;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginCapability;
import org.yanoproject.api.plugin.PluginContext;

import java.util.Set;

/**
 * Build-only directory plugin for the packaged JVM smokes and the downstream-consumer check. Its node
 * plugin registers an inbound N2N extension through the Host API and then logs {@link #SUCCESS_MARKER};
 * the health and metrics contributions are activated by the catalog. On an {@code app-ledger} host it
 * supplies the app-only network {@link #APP_ONLY_NETWORK_MAGIC}. When the runtime globals carry
 * {@link #ECHOED_GLOBAL} it registers that value as the service {@link #ECHO_SERVICE}, so a host can
 * prove a contributed global reached its plugins. As a directory bundle it cannot own an assembly
 * step (embedded owners only), so it does all of this during {@code init}, which the Host API allows.
 * The fixture is never included in normal artifacts.
 */
public final class PluginConformanceFixture implements NodePlugin {
    public static final String BUNDLE_ID = "org.yanoproject.fixture.plugin-conformance";
    public static final String SUCCESS_MARKER =
            "ADR-011.2 node-plugin conformance activated through catalog";
    static final int INBOUND_PROTOCOL_ID = 100;
    /** App-only network magic supplied on an app-ledger host; not a Cardano network magic. */
    public static final long APP_ONLY_NETWORK_MAGIC = 6464;
    /** Runtime global contributed by the downstream-consumer check. */
    public static final String ECHOED_GLOBAL = "yano.fixture.consumer-check";
    /** Service key under which the fixture echoes {@link #ECHOED_GLOBAL}. */
    public static final String ECHO_SERVICE = BUNDLE_ID + ".echoed-global";

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
        if (host.nodeConfig().getMode() == RuntimeMode.APP_LEDGER) {
            host.n2n().requireAppOnlyNetwork(APP_ONLY_NETWORK_MAGIC);
        }
        host.n2n().registerInbound(INBOUND_PROTOCOL_ID, () -> new AppMsgSubmissionServerAgent(
                AppMsgSubmissionConfig.builder().chainIds(Set.of(BUNDLE_ID)).build()));
        Object echoed = host.runtimeOptions().globals().get(ECHOED_GLOBAL);
        if (echoed != null) {
            ctx.registerService(ECHO_SERVICE, echoed.toString());
        }
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
