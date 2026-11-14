package org.yanoproject.api.host;

import org.yanoproject.runtime.plugins.PluginProviderRegistry;

/**
 * Infrastructure capability obtained through the existing PluginContext.getService mechanism.
 * Lifecycle, configuration, events, scheduling, health and metrics retain their existing SPIs.
 * This capability adds no extension loader and does not expose a node implementation.
 */
public interface HostApi {
    String SERVICE_KEY = "org.yanoproject.host-api";

    HostApiVersion version();
    N2NExtensionRegistry n2n();
    PluginProviderRegistry providers();
}
