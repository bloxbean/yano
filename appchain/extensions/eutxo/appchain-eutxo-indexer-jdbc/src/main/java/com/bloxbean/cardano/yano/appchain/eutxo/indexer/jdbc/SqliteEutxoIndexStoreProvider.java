package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStoreContext;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStoreProvider;

public final class SqliteEutxoIndexStoreProvider
        implements EutxoIndexStoreProvider {
    public static final String TYPE = "jdbc";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public EutxoIndexStore open(EutxoIndexStoreContext context) {
        return SqliteEutxoIndexStore.open(context);
    }
}
