package com.bloxbean.cardano.yano.appchain.eutxo.indexer.memory;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing.EutxoIndexStoreConformance;

final class InMemoryEutxoIndexStoreTest extends EutxoIndexStoreConformance {
    @Override
    protected EutxoIndexStore open(IndexIdentity identity) {
        return new InMemoryEutxoIndexStore(identity);
    }
}
