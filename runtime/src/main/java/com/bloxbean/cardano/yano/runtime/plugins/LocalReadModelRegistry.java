package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Host-owned, lifecycle-safe registry of storage-neutral local read models. */
public final class LocalReadModelRegistry
        implements LocalReadModelHost, AutoCloseable {
    private final Map<Key, LocalReadModel> models = new LinkedHashMap<>();
    private boolean closed;

    @Override
    public synchronized AutoCloseable register(
            String modelId,
            String chainId,
            LocalReadModel model
    ) {
        requireOpen();
        Key key = new Key(identity(modelId), identity(chainId));
        Objects.requireNonNull(model, "model");
        if (models.putIfAbsent(key, model) != null) {
            throw new IllegalStateException(
                    "local read model is already registered for " + key);
        }
        return () -> {
            synchronized (LocalReadModelRegistry.this) {
                models.remove(key, model);
            }
        };
    }

    @Override
    public LocalReadModelResult query(
            String modelId,
            String chainId,
            String operation,
            byte[] boundedRequest
    ) {
        LocalReadModel model;
        synchronized (this) {
            requireOpen();
            model = models.get(
                    new Key(identity(modelId), identity(chainId)));
        }
        return model == null
                ? LocalReadModelResult.unavailable()
                : model.query(identity(operation), boundedRequest.clone());
    }

    @Override
    public synchronized void close() {
        closed = true;
        models.clear();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "local read-model registry is closed");
        }
    }

    private static String identity(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim();
        if (normalized.isEmpty() || normalized.length() > 160
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(
                    "invalid local read-model identity");
        }
        return normalized;
    }

    private record Key(String modelId, String chainId) {
    }
}
