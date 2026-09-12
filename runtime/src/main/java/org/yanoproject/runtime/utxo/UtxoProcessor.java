package org.yanoproject.runtime.utxo;

import org.yanoproject.api.events.BlockAppliedEvent;
import org.rocksdb.ColumnFamilyHandle;

/**
 * Prepares per-block apply context (e.g., batched input prefetch) to reduce JNI/IO.
 */
public interface UtxoProcessor {
    ApplyContext prepare(BlockAppliedEvent event, ColumnFamilyHandle cfUnspent);

    interface ApplyContext extends AutoCloseable {
        byte[] getUnspent(byte[] outpointKey);
        @Override default void close() {}
    }
}

