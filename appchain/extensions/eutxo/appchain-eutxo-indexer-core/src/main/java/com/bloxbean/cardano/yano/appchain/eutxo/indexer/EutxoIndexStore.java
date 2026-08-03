package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

public interface EutxoIndexStore extends AutoCloseable {
    IndexIdentity identity();

    EutxoIndexWrite begin(SourcePoint source);

    IndexCheckpoint checkpoint();

    void rollbackTo(SourcePoint source);

    EutxoIndexReader reader();

    @Override
    void close();
}
