package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

/** Cataloged transaction-building API for the Cardano EUTxO bridge. */
public final class EutxoBridgeDomainApiProvider implements DomainApiProvider {
    public static final String ID =
            "com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano";

    @Override public String id() { return ID; }
    @Override public DomainApi create(DomainApiContext context) {
        return new EutxoBridgeDomainApi(context);
    }
}
