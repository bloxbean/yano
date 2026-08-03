package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/** Demo-only provider for the order approval and local-outbox showcase. */
public final class ShowcaseCompositeStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return ShowcaseCompositePreset.ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("showcase-composite requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return ShowcaseCompositePreset.create(context);
    }
}
