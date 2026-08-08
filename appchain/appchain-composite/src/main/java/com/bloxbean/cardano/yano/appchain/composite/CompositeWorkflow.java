package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;

/**
 * Declared deterministic coordinator for one versioned cross-component command route.
 * Expected business-precondition failures, duplicate commands, and conflicts
 * must be handled as deterministic per-message no-ops. Throwing for such an
 * admitted message can poison the proposer pool and repeatedly abort block
 * construction. Exceptions are reserved for state corruption, impossible
 * invariants, or framework contract violations. The routed execution context
 * retains the original globally authenticated block identity and indexes.
 */
public interface CompositeWorkflow {
    WorkflowDescriptor descriptor();

    default AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return AppStateMachine.AdmissionResult.accept();
    }

    /** Candidate-height admission without exposing undeclared component state. */
    default AppStateMachine.AdmissionResult validateForBlock(
            AppMessage routedMessage,
            long candidateHeight
    ) {
        return validate(routedMessage);
    }

    void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context);
}
