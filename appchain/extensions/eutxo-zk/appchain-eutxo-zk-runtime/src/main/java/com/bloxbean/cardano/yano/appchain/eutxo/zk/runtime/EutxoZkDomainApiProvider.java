package com.bloxbean.cardano.yano.appchain.eutxo.zk.runtime;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.ledger.EutxoDomainApi;

/** EUTxO query surface owned by the integrated ZeroJ runtime bundle. */
public final class EutxoZkDomainApiProvider implements DomainApiProvider {
    public static final String ID =
            "com.bloxbean.cardano.yano.appchain.eutxo.zk.runtime";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new EutxoDomainApi(context);
    }
}
