package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.util.Optional;
import java.util.List;

/**
 * Optional, node-local query surface for a mempool-inclusive UTXO view.
 *
 * <p>This is deliberately separate from {@link LedgerQuery}: canonical ledger
 * queries remain stable by default, while clients that are deliberately
 * constructing transaction chains can opt into the node's transient view.</p>
 */
public interface MempoolQueryGateway {
    MempoolQueryGateway UNAVAILABLE = new MempoolQueryGateway() { };

    default Optional<Utxo> resolveUtxo(Outpoint outpoint) {
        return Optional.empty();
    }

    /** Transient listing, filtered and ordered before 1-based pagination. Null asset means all assets. */
    default List<Utxo> listUtxos(String addressOrCredential, boolean paymentCredential,
                               String asset, int page, int count, boolean descending) {
        throw new UnsupportedOperationException("Mempool UTxO listings unavailable");
    }

    /** Return the encoded reference-script bytes, including mempool outputs. */
    default Optional<byte[]> getScriptRefBytesByHash(String scriptHash) {
        return Optional.empty();
    }
}
