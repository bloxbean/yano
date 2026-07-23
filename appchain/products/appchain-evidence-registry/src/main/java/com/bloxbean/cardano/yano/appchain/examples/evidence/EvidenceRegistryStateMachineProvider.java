package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/** ServiceLoader entry point for one configured evidence-registry chain. */
public final class EvidenceRegistryStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return EvidenceContract.STATE_MACHINE_ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("The evidence-registry state machine requires chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return new EvidenceRegistryStateMachine(EvidenceRegistryConfig.from(context));
    }
}
