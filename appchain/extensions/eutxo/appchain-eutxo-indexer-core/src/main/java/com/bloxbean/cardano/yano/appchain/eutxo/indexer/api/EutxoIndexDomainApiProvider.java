package com.bloxbean.cardano.yano.appchain.eutxo.indexer.api;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoLocalReadModel;

public final class EutxoIndexDomainApiProvider implements DomainApiProvider {
    @Override
    public String id() {
        return EutxoLocalReadModel.MODEL_ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new EutxoIndexDomainApi(context);
    }
}
