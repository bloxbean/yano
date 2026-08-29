package com.bloxbean.cardano.yano.api.utxo;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;

import java.util.Optional;

/** Close-scoped, ordered and coordinate-bound view of live stake balances. */
public interface StakeBalanceView extends AutoCloseable {
    CanonicalBlockReference coordinate();

    boolean advance();

    StakeCredentialBalance current();

    /**
     * Open pointer UTXOs from this view's exact RocksDB snapshot. An empty
     * result means the versioned pointer index is unavailable at the view's
     * canonical coordinate and the caller must use the historical scan.
     */
    default Optional<PointerUtxoView> openPointerUtxoView(long maxCreationSlot) {
        return Optional.empty();
    }

    @Override
    void close();
}
