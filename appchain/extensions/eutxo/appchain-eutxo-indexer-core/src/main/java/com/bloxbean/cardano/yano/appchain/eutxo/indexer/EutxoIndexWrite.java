package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

public interface EutxoIndexWrite extends AutoCloseable {
    void apply(EutxoIndexEvent event);

    void commit(IndexCheckpoint checkpoint);

    void abort();

    @Override
    default void close() {
        abort();
    }
}
