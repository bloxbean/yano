package com.bloxbean.cardano.yano.runtime.migration;

import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;

import java.util.Map;

/**
 * Context shared with startup migrations that need storage access and resolved
 * runtime globals.
 */
public record StartupMigrationContext(RocksDbSupplier rocksDbSupplier,
                                      Map<String, Object> globals,
                                      String chainstatePath) {
    public StartupMigrationContext {
        globals = globals != null ? globals : Map.of();
    }

    public boolean bool(String key, boolean defaultValue) {
        Object value = globals.get(key);
        if (value instanceof Boolean b) return b;
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
