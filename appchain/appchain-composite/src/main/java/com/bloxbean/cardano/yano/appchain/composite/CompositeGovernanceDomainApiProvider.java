package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

/** Cataloged operator API for governed composite-profile commands. */
public final class CompositeGovernanceDomainApiProvider
        implements DomainApiProvider {
    public static final String ID =
            "com.bloxbean.cardano.yano.appchain.composite";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new CompositeGovernanceDomainApi(context);
    }
}
