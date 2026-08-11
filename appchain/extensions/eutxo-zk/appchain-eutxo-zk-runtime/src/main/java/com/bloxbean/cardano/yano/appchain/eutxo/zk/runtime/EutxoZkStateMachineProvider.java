package com.bloxbean.cardano.yano.appchain.eutxo.zk.runtime;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.ledger.EutxoStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.ZerojPoseidonValidityProvider;

/** Integrated bundle root that owns the optional ZeroJ capability directly. */
public final class EutxoZkStateMachineProvider
        implements AppStateMachineProvider {
    private final EutxoStateMachineProvider delegate =
            new EutxoStateMachineProvider(new ZerojPoseidonValidityProvider());

    @Override public String id() { return delegate.id(); }
    @Override public AppStateMachine create() { return delegate.create(); }
    @Override public AppStateMachine create(AppStateMachineContext context) {
        return delegate.create(context);
    }
}
