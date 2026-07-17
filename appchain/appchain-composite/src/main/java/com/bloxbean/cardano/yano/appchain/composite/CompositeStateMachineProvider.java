package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.composite.stock.CompositeStockPresets;

/** Manifested provider for the configuration-only stock composite presets. */
public final class CompositeStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return CompositeStateMachine.ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("The composite state machine requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return CompositeStockPresets.create(context);
    }
}
