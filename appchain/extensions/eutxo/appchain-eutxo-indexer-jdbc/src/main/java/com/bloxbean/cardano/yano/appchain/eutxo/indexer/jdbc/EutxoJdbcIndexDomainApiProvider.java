package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.api.EutxoIndexDomainApiProvider;

/** Bundle-local provider entry point for the storage-neutral index domain API. */
public final class EutxoJdbcIndexDomainApiProvider implements DomainApiProvider {
    private final EutxoIndexDomainApiProvider delegate = new EutxoIndexDomainApiProvider();

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return delegate.create(context);
    }
}
