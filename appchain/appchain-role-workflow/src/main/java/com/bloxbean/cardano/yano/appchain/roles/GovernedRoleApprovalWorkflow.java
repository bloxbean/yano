package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;

import java.util.List;
import java.util.Objects;

/**
 * Genesis-bound role-approval route for the authenticated-map v1 assembly.
 * Phase B commits and gates the route; Phase D supplies its command processor.
 */
public final class GovernedRoleApprovalWorkflow implements CompositeWorkflow {
    public static final String WORKFLOW_ID = "role-approval-v1";
    public static final String TOPIC = "role-approvals.command.v1";
    public static final String PRODUCT_VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor;

    public GovernedRoleApprovalWorkflow(
            WorkflowDescriptor descriptor,
            ComponentGeneration actors,
            ComponentGeneration approvals
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        if (!WORKFLOW_ID.equals(descriptor.workflowId())
                || !TOPIC.equals(descriptor.topic())
                || !descriptor.participants().equals(List.of(actors, approvals))) {
            throw new IllegalArgumentException("invalid governed role-approval descriptor");
        }
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return AppStateMachine.AdmissionResult.reject("GOVERNED_ROUTE_UNSUPPORTED");
    }

    @Override
    public void apply(AppBlock routedBlock, CompositeWorkflowContext context) {
        // The route is deliberately inert until its Phase-D processor is installed.
    }
}
