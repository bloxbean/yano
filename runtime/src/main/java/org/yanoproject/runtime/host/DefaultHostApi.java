package org.yanoproject.runtime.host;

import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import java.util.Objects;

/** Adapts the existing host-owned registries; it owns no additional runtime or lifecycle. */
public record DefaultHostApi(N2NExtensionRegistry n2n, PluginProviderRegistry providers,
                             HostLifecycleRegistry lifecycle) implements HostApi {
    public DefaultHostApi(N2NExtensionRegistry n2n, PluginProviderRegistry providers) {
        this(n2n, providers, service -> {
            throw new UnsupportedOperationException("Host lifecycle registration requires an assembly-owned registry");
        });
    }
    public DefaultHostApi {
        Objects.requireNonNull(n2n, "n2n");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(lifecycle, "lifecycle");
    }
    @Override public HostApiVersion version() { return HostApiVersion.CURRENT; }
}
