package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.List;
import java.util.Objects;

/** Final-v1 command route coordinating declared authorization and map state views. */
public final class AuthenticatedMapAuthorizationWorkflow implements CompositeWorkflow {
    public static final String WORKFLOW_ID = "authenticated-map-authorization-v1";
    public static final String PRODUCT_VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration mapGeneration;
    private final AuthenticatedMapComponent map;

    public AuthenticatedMapAuthorizationWorkflow(
            WorkflowDescriptor descriptor,
            ComponentGeneration actors,
            ComponentGeneration approvals,
            ComponentGeneration mapGeneration,
            AuthenticatedMapComponent map
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.mapGeneration = Objects.requireNonNull(mapGeneration, "mapGeneration");
        this.map = Objects.requireNonNull(map, "map");
        if (!WORKFLOW_ID.equals(descriptor.workflowId())
                || !AuthenticatedMapContract.DEFAULT_TOPIC.equals(descriptor.topic())
                || !descriptor.participants().equals(
                List.of(actors, approvals, mapGeneration))
                || !map.descriptor().generation().equals(mapGeneration)) {
            throw new IllegalArgumentException(
                    "invalid authenticated-map authorization workflow descriptor");
        }
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return map.validateCommand(routedMessage);
    }

    @Override
    public AppStateMachine.AdmissionResult validateForBlock(
            AppMessage routedMessage,
            long candidateHeight
    ) {
        return map.validateCommandForBlock(routedMessage, candidateHeight);
    }

    @Override
    public void apply(AppBlock routedBlock, CompositeWorkflowContext context) {
        map.applyCommands(routedBlock, context.state(mapGeneration));
    }
}
