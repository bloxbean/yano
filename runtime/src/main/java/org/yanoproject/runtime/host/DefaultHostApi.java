package org.yanoproject.runtime.host;

import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import java.util.Objects;

/** Adapts the existing host-owned registries; it owns no additional runtime or lifecycle. */
public record DefaultHostApi(N2NExtensionRegistry n2n, PluginProviderRegistry providers) implements HostApi {
    public DefaultHostApi {
        Objects.requireNonNull(n2n, "n2n");
        Objects.requireNonNull(providers, "providers");
    }
    @Override public HostApiVersion version() { return HostApiVersion.CURRENT; }
}
