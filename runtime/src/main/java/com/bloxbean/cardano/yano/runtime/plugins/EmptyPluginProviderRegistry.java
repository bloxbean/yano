package com.bloxbean.cardano.yano.runtime.plugins;

import java.util.List;
import java.util.Optional;

final class EmptyPluginProviderRegistry implements PluginProviderRegistry {
    static final EmptyPluginProviderRegistry INSTANCE = new EmptyPluginProviderRegistry();

    private EmptyPluginProviderRegistry() {
    }

    @Override
    public <P> Optional<P> find(Class<P> providerType, String selector) {
        return Optional.empty();
    }

    @Override
    public <P> List<String> names(Class<P> providerType) {
        return List.of();
    }
}
