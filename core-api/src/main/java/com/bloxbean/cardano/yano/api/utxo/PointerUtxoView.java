package com.bloxbean.cardano.yano.api.utxo;

/** Close-scoped cursor over the pointer UTXOs in a boundary stake view. */
public interface PointerUtxoView extends AutoCloseable {
    boolean advance();

    PointerUtxo current();

    @Override
    void close();
}
