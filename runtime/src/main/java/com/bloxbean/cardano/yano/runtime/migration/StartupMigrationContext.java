package com.bloxbean.cardano.yano.runtime.migration;

import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;

import java.util.Map;

public record StartupMigrationContext(DirectRocksDBChainState chainState,
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
