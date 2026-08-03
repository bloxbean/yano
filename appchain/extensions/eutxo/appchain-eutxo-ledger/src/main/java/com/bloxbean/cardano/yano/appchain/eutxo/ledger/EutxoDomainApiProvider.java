package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

/** Read-only transaction explorer API for the bundled EUTxO state machine. */
public final class EutxoDomainApiProvider implements DomainApiProvider {
    public static final String ID =
            "com.bloxbean.cardano.yano.appchain.eutxo";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new EutxoDomainApi(context);
    }
}
