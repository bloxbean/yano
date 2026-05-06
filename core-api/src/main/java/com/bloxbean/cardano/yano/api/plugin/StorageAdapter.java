package com.bloxbean.cardano.yano.api.plugin;

import com.bloxbean.cardano.yaci.events.api.EventMetadata;

public interface StorageAdapter extends AutoCloseable {
    default void onBlockApplied(Object blockAppliedEvent, EventMetadata meta) {}
    default void onRollback(Object rollbackEvent, EventMetadata meta) {}
    @Override default void close() {}
}

