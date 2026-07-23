package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;

/** Manifested provider for the configuration-only stock composite presets. */
public final class EvidenceCompositeStateMachineProvider implements AppStateMachineProvider {
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
        return EvidenceCompositePresets.create(context);
    }
}
