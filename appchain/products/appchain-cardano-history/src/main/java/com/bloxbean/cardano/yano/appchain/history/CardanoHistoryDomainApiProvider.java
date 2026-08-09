package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

import java.util.Objects;

/** Read-only, bundle-owned Cardano History query API. */
public final class CardanoHistoryDomainApiProvider implements DomainApiProvider {
    @Override
    public String id() {
        return CardanoHistoryProduct.BUNDLE_ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new CardanoHistoryDomainApi(Objects.requireNonNull(context, "context"));
    }
}
