package org.yanoproject.runtime.host;

import org.yanoproject.api.config.PluginConfigValues;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Adapts the existing host-owned registries; it owns no additional runtime or lifecycle. */
public final class DefaultHostApi implements HostApi {
    private final N2NExtensionRegistry n2n;
    private final PluginProviderRegistry providers;
    private final HostLifecycleRegistry lifecycle;
    private final YanoConfig config;
    private final RuntimeOptions options;
    private final Supplier<String> networkName;
    private final Function<String, Map<String, Object>> bundleConfigs;

    /**
     * @param config host configuration; {@link #nodeConfig()} copies it on every call
     * @param options host options; their globals are deep-snapshotted here, once
     * @param networkName host network label
     * @param bundleConfigs selected bundle configuration by bundle id
     */
    public DefaultHostApi(N2NExtensionRegistry n2n, PluginProviderRegistry providers,
                          HostLifecycleRegistry lifecycle, YanoConfig config, RuntimeOptions options,
                          Supplier<String> networkName, Function<String, Map<String, Object>> bundleConfigs) {
        this.n2n = Objects.requireNonNull(n2n, "n2n");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.config = config.toBuilder().build();
        this.options = new RuntimeOptions(options.events(), options.plugins(),
                PluginConfigValues.immutableCopy(options.globals()));
        this.networkName = Objects.requireNonNull(networkName, "networkName");
        this.bundleConfigs = Objects.requireNonNull(bundleConfigs, "bundleConfigs");
    }

    @Override public HostApiVersion version() { return HostApiVersion.CURRENT; }
    @Override public N2NExtensionRegistry n2n() { return n2n; }
    @Override public PluginProviderRegistry providers() { return providers; }
    @Override public HostLifecycleRegistry lifecycle() { return lifecycle; }
    @Override public YanoConfig nodeConfig() { return config.toBuilder().build(); }
    @Override public String networkName() { return networkName.get(); }

    @Override
    public RuntimeOptions runtimeOptions() {
        return new RuntimeOptions(options.events(), options.plugins(), options.globals());
    }

    @Override
    public Map<String, Object> bundleConfig(String bundleId) {
        return bundleConfigs.apply(Objects.requireNonNull(bundleId, "bundleId"));
    }
}
