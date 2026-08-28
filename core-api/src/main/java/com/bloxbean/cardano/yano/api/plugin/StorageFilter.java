package com.bloxbean.cardano.yano.api.plugin;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;

/**
 * Filter interface for controlling which data gets persisted to storage.
 * <p>
 * Implementations can selectively accept or reject UTXO outputs before they are
 * written to the UTXO store. Multiple filters are composed into a chain — all
 * filters must accept for an output to be stored.
 * <p>
 * Plugins can register custom filters via {@link PluginContext#registerStorageFilter(StorageFilter)}.
 * Built-in filters (e.g., address-based) are configured via YAML.
 *
 * @see UtxoFilterContext
 */
public interface StorageFilter {

    /**
     * Decide whether a transaction output should be stored.
     *
     * @param ctx    UTXO-specific fields (address, lovelace, assets, output index)
     * @param block  the full Block (era, slot, issuer, all transactions)
     * @param txBody the specific TransactionBody this output belongs to
     * @return true to keep (store), false to reject (skip storage)
     */
    default boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
        return true;
    }

    /**
     * Decide whether a native Byron transaction output should be stored.
     * Existing filters remain pass-through until they opt into Byron inspection.
     */
    default boolean acceptByronUtxoOutput(
            UtxoFilterContext ctx, ByronMainBlock block, ByronTx tx) {
        return true;
    }

    /**
     * Filter priority. Lower values run earlier in the chain.
     * Default is 100.
     */
    default int priority() {
        return 100;
    }
}
