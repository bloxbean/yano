package org.yanoproject.api.host;

import java.util.Optional;

/** Read access to the services registered by the currently initialized plugin generation. */
public interface PluginServiceLookup {
    /**
     * Resolves a plugin-registered service on every call.
     *
     * @param key service key
     * @param type expected service type
     * @param <T> service type
     * @return the service, or empty before plugin initialization, after close, or when absent
     */
    <T> Optional<T> service(String key, Class<T> type);
}
