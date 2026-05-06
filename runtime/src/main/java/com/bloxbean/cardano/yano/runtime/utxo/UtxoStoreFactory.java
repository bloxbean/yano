package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Factory to create the default UTXO backend implementation.
 */
public final class UtxoStoreFactory {
    private UtxoStoreFactory() {}

    public static UtxoStoreWriter create(RocksDbSupplier rocks, Logger log, Map<String, Object> cfg) {
        return new DefaultUtxoStore(rocks, log, cfg);
    }
}
