package org.yanoproject.api.host;

import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;

import java.util.Map;

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

    /** Optional on earlier hosts; require it before opening extension resources. */
    default HostLifecycleRegistry lifecycle() {
        throw new UnsupportedOperationException("Host does not support pre-publication extension lifecycle");
    }

    /**
     * Host node configuration (Host API 1.2).
     *
     * @return a fresh copy on every call, so one plugin cannot change another's view
     */
    default YanoConfig nodeConfig() {
        throw new UnsupportedOperationException("Host does not expose node configuration");
    }

    /**
     * Host runtime options (Host API 1.2).
     *
     * @return a new value on every call whose globals are a bounded, immutable deep snapshot
     */
    default RuntimeOptions runtimeOptions() {
        throw new UnsupportedOperationException("Host does not expose runtime options");
    }

    /**
     * Network label of this host (Host API 1.2): the Cardano network name in Cardano mode, and
     * {@code "app-" + magic} in app-ledger mode once a plugin supplied the app-only network.
     *
     * @return network label
     * @throws IllegalStateException in app-ledger mode before
     *                               {@link N2NExtensionRegistry#requireAppOnlyNetwork(long)}
     */
    default String networkName() {
        throw new UnsupportedOperationException("Host does not expose its network name");
    }

    /**
     * Unrestricted configuration of one selected bundle (Host API 1.2). Bundle configuration can contain
     * credentials; this read is for trusted host-class-path code only.
     *
     * @param bundleId selected bundle id
     * @return immutable bundle configuration, or an empty map
     */
    default Map<String, Object> bundleConfig(String bundleId) {
        throw new UnsupportedOperationException("Host does not expose bundle configuration");
    }
}
