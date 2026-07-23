package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/** Stock provider for domain actors and role-gated payload approvals. */
public final class RoleApprovalsStateMachineProvider implements AppStateMachineProvider {
    public static final String ID = "role-approvals";

    @Override public String id() { return ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("role-approvals requires app-chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return RoleApprovalsPreset.create(context);
    }
}
