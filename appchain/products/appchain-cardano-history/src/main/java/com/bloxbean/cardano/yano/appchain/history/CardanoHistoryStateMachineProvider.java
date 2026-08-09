package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/** Thin product provider that resolves a released preset to reusable ADR-028 components. */
public final class CardanoHistoryStateMachineProvider implements AppStateMachineProvider {
    @Override public String id() { return CardanoHistoryProduct.STATE_MACHINE_ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("cardano-history requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return CardanoHistoryStateMachines.create(context);
    }
}
