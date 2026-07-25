package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

public interface EutxoIndexStoreProvider {
    String type();

    EutxoIndexStore open(EutxoIndexStoreContext context);
}
