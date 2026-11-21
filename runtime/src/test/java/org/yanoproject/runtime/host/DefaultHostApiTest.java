package org.yanoproject.runtime.host;

import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.UpstreamConfig;
import org.yanoproject.api.config.UpstreamDiscoveryConfig;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultHostApiTest {
    @Test
    void nodeConfigAndRuntimeOptionsAreIsolatedSnapshots() {
        List<Object> chains = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("chain-id", "a"))));
        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("example.chains", chains);
        globals.put("example.flag", "on");
        YanoConfig config = YanoConfig.serverOnly(0);
        var host = new DefaultHostApi(new DefaultN2NExtensionRegistry(), PluginProviderRegistry.empty(),
                DefaultHostLifecycleRegistryTest.newRegistry(), config,
                new RuntimeOptions(EventsOptions.defaults(), PluginsOptions.defaults(), globals),
                () -> "devnet", id -> Map.of("secret", id));

        globals.put("example.flag", "off");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstChain = (Map<String, Object>) chains.getFirst();
        firstChain.put("chain-id", "mutated");
        chains.add(Map.of());
        config.setServerPort(1);

        RuntimeOptions first = host.runtimeOptions();
        RuntimeOptions second = host.runtimeOptions();
        assertThat(first).isNotSameAs(second);
        assertThat(first.globals()).containsEntry("example.flag", "on");
        assertThat((List<?>) first.globals().get("example.chains")).hasSize(1)
                .first().isEqualTo(Map.of("chain-id", "a"));
        assertThatThrownBy(() -> first.globals().put("example.flag", "x"))
                .isInstanceOf(UnsupportedOperationException.class);

        YanoConfig copy = host.nodeConfig();
        assertThat(copy).isNotSameAs(host.nodeConfig());
        assertThat(copy.getServerPort()).isZero();
        copy.setServerPort(2);
        assertThat(host.nodeConfig().getServerPort()).isZero();

        assertThat(host.version()).isEqualTo(new HostApiVersion(1, 2));
        assertThat(host.networkName()).isEqualTo("devnet");
        assertThat(host.bundleConfig("com.example.bundle")).containsEntry("secret", "com.example.bundle");
    }

    @Test
    void nodeConfigIsolatesMutableCollectionsFromCallersAndTheSource() {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setBootstrapAddresses(new ArrayList<>(List.of("addr_test1source")));
        var host = new DefaultHostApi(new DefaultN2NExtensionRegistry(), PluginProviderRegistry.empty(),
                DefaultHostLifecycleRegistryTest.newRegistry(), config,
                new RuntimeOptions(EventsOptions.defaults(), PluginsOptions.defaults(), Map.of()),
                () -> "devnet", id -> Map.of());

        host.nodeConfig().getBootstrapAddresses().clear();
        assertThat(host.nodeConfig().getBootstrapAddresses()).containsExactly("addr_test1source");

        config.getBootstrapAddresses().add("addr_test1later");
        assertThat(host.nodeConfig().getBootstrapAddresses()).containsExactly("addr_test1source");
    }

    @Test
    void nodeConfigIsolatesNestedUpstreamConfiguration() {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUpstream(UpstreamConfig.builder()
                .discovery(UpstreamDiscoveryConfig.builder()
                        .seeds(new ArrayList<>(List.of("seed:3001"))).build())
                .build());
        var host = new DefaultHostApi(new DefaultN2NExtensionRegistry(), PluginProviderRegistry.empty(),
                DefaultHostLifecycleRegistryTest.newRegistry(), config,
                new RuntimeOptions(EventsOptions.defaults(), PluginsOptions.defaults(), Map.of()),
                () -> "devnet", id -> Map.of());

        host.nodeConfig().getUpstream().getValidation().getStart().setMode("checkpoint");
        host.nodeConfig().getUpstream().getDiscovery().getSeeds().clear();
        assertThat(host.nodeConfig().getUpstream().getValidation().getStart().getMode()).isEqualTo("immediate");
        assertThat(host.nodeConfig().getUpstream().getDiscovery().getSeeds()).containsExactly("seed:3001");

        config.getUpstream().getDiscovery().getSeeds().add("seed:3002");
        config.getUpstream().getValidation().getStart().setMode("era");
        assertThat(host.nodeConfig().getUpstream().getDiscovery().getSeeds()).containsExactly("seed:3001");
        assertThat(host.nodeConfig().getUpstream().getValidation().getStart().getMode()).isEqualTo("immediate");
    }

    @Test
    void appOnlyNetworkRejectsCardanoMagicsConflictsSharedHostsAndLateCalls() {
        var shared = new DefaultN2NExtensionRegistry();
        assertThatThrownBy(() -> shared.requireAppOnlyNetwork(77))
                .isInstanceOf(UnsupportedOperationException.class);

        var appOnly = new DefaultN2NExtensionRegistry(false);
        assertThat(appOnly.appOnlyNetwork()).isEmpty();
        appOnly.requireAppOnlyNetwork(77);
        appOnly.requireAppOnlyNetwork(77);
        assertThatThrownBy(() -> appOnly.requireAppOnlyNetwork(78))
                .hasMessageContaining("conflicting app-only network magics 77 and 78");
        assertThatThrownBy(() -> appOnly.requireAppOnlyNetwork(0x1_0000_0000L))
                .hasMessageContaining("unsigned 32-bit");
        for (long cardano : new long[]{764_824_073L, 1L, 2L, 4L, 42L}) {
            var fresh = new DefaultN2NExtensionRegistry(false);
            assertThatThrownBy(() -> fresh.requireAppOnlyNetwork(cardano))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("App-only network magic " + cardano
                            + " is a Cardano network magic; use a distinct app network identity");
            assertThat(fresh.appOnlyNetwork()).isEmpty();
        }
        appOnly.seal();
        assertThatThrownBy(() -> appOnly.requireAppOnlyNetwork(77))
                .hasMessageContaining("before sessions start");
        assertThat(appOnly.appOnlyNetwork()).hasValue(77);
    }
}
