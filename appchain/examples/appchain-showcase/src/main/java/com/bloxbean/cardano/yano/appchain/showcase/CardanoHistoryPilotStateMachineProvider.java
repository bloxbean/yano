package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/** Showcase-only provider for the ADR-028 full-profile qualification chain. */
public final class CardanoHistoryPilotStateMachineProvider implements AppStateMachineProvider {
    @Override public String id() { return CardanoHistoryPilotPreset.ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("cardano-history-pilot requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return CardanoHistoryPilotPreset.create(context);
    }
}
