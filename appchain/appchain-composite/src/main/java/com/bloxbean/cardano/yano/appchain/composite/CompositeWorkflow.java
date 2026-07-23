package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;

/**
 * Declared deterministic coordinator for one versioned cross-component command route.
 * Expected business-precondition failures, duplicate commands, and conflicts
 * must be handled as deterministic per-message no-ops. Throwing for such an
 * admitted message can poison the proposer pool and repeatedly abort block
 * construction. Exceptions are reserved for state corruption, impossible
 * invariants, or framework contract violations. The supplied block is a
 * routed projection whose finality certificate is deliberately empty; the
 * original full-block certificate cannot authenticate that synthetic block.
 */
public interface CompositeWorkflow {
    WorkflowDescriptor descriptor();

    default AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return AppStateMachine.AdmissionResult.accept();
    }

    void apply(AppBlock routedBlock, CompositeWorkflowContext context);
}
