package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelContext;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReadModelContributionRegistryTest {
    @Test
    void usesOwningBundleConfigAndClosesProductsInReverseOrder() {
        List<String> events = new ArrayList<>();
        Map<String, LocalReadModelProvider> contributions = new LinkedHashMap<>();
        contributions.put("z-model", provider("z-model", events));
        contributions.put("a-model", provider("a-model", events));
        PluginProviderRegistry providers = registry(contributions, Map.of(
                "a-model", "bundle-a",
                "z-model", "bundle-z"));
        var lifecycle = new LocalReadModelContributionRegistry(
                providers,
                bundle -> Map.of("owner", bundle),
                "preview",
                AppChainGateways.empty(),
                LocalReadModelHost.unavailable());

        lifecycle.resume();
        lifecycle.resume();
        assertThat(events).containsExactly(
                "start:a-model:bundle-a", "start:z-model:bundle-z");

        lifecycle.sealAndAwait();
        assertThat(events).containsExactly(
                "start:a-model:bundle-a", "start:z-model:bundle-z",
                "close:z-model", "close:a-model");

        lifecycle.resume();
        lifecycle.close();
        lifecycle.close();
        assertThat(events).containsExactly(
                "start:a-model:bundle-a", "start:z-model:bundle-z",
                "close:z-model", "close:a-model",
                "start:a-model:bundle-a", "start:z-model:bundle-z",
                "close:z-model", "close:a-model");
    }

    private static LocalReadModelProvider provider(
            String id,
            List<String> events
    ) {
        return new LocalReadModelProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public AutoCloseable start(LocalReadModelContext context) {
                events.add("start:" + id + ":" + context.bundleConfig().get("owner"));
                return () -> events.add("close:" + id);
            }
        };
    }

    private static PluginProviderRegistry registry(
            Map<String, LocalReadModelProvider> contributions,
            Map<String, String> owners
    ) {
        return new PluginProviderRegistry() {
            @Override
            public <P> Optional<P> find(Class<P> providerType, String selector) {
                if (providerType != LocalReadModelProvider.class) {
                    return Optional.empty();
                }
                return Optional.ofNullable(contributions.get(selector))
                        .map(providerType::cast);
            }

            @Override
            public <P> List<String> names(Class<P> providerType) {
                return providerType == LocalReadModelProvider.class
                        ? List.copyOf(contributions.keySet()) : List.of();
            }

            @Override
            public <P> Optional<String> contributionOwner(
                    Class<P> providerType,
                    String selector
            ) {
                return providerType == LocalReadModelProvider.class
                        ? Optional.ofNullable(owners.get(selector)) : Optional.empty();
            }
        };
    }
}
