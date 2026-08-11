package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Direct test registry mirroring the stdlib bundle's manifested machines. */
final class StdlibTestPluginProviders {
    private static final Map<String, AppStateMachineProvider> MACHINES = List.of(
                    new StdlibStateMachineProviders.AuthenticatedMapProvider(),
                    new StdlibStateMachineProviders.ApprovalsProvider(),
                    new StdlibStateMachineProviders.BalancesProvider(),
                    new StdlibStateMachineProviders.DocTrailProvider(),
                    new StdlibStateMachineProviders.EpochParamsProvider(),
                    new StdlibStateMachineProviders.EpochGovernanceProvider(),
                    new StdlibStateMachineProviders.EpochStakeProvider(),
                    new StdlibStateMachineProviders.KvRegistryProvider())
            .stream()
            .collect(Collectors.toUnmodifiableMap(
                    AppStateMachineProvider::id, Function.identity()));

    private StdlibTestPluginProviders() {
    }

    static PluginProviderRegistry registry() {
        return new PluginProviderRegistry() {
            @Override
            public <P> Optional<P> find(Class<P> providerType, String selector) {
                if (providerType != AppStateMachineProvider.class) {
                    return Optional.empty();
                }
                return Optional.ofNullable(MACHINES.get(selector))
                        .map(providerType::cast);
            }

            @Override
            public <P> List<String> names(Class<P> providerType) {
                if (providerType != AppStateMachineProvider.class) {
                    return List.of();
                }
                return MACHINES.keySet().stream().sorted().toList();
            }
        };
    }
}
