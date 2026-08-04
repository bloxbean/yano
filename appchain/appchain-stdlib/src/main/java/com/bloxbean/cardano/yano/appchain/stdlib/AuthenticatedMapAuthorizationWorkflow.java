package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract.*;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.List;
import java.util.Objects;

/** Final-v1 command route coordinating declared authorization and map state views. */
public final class AuthenticatedMapAuthorizationWorkflow implements CompositeWorkflow {
    public static final String WORKFLOW_ID = "authenticated-map-authorization-v1";
    public static final String PRODUCT_VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration actorsGeneration;
    private final ComponentGeneration approvalsGeneration;
    private final ComponentGeneration mapGeneration;
    private final AuthenticatedMapComponent map;
    private final AuthenticatedMapDirectAuthorizer directAuthorizer;

    public AuthenticatedMapAuthorizationWorkflow(
            WorkflowDescriptor descriptor,
            ComponentGeneration actors,
            ComponentGeneration approvals,
            ComponentGeneration mapGeneration,
            AuthenticatedMapComponent map
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.actorsGeneration = Objects.requireNonNull(actors, "actors");
        this.approvalsGeneration = Objects.requireNonNull(approvals, "approvals");
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
        var governed = map.genesis().governedGenesis();
        this.directAuthorizer = governed == null ? null
                : new AuthenticatedMapDirectAuthorizer(
                map.genesis().chainId(),
                AuthenticatedMapContract.genesisId(map.genesis()), governed.limits());
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return validateActorSignatures(
                map.validateCommand(routedMessage), routedMessage);
    }

    @Override
    public AppStateMachine.AdmissionResult validateForBlock(
            AppMessage routedMessage,
            long candidateHeight
    ) {
        return validateActorSignatures(
                map.validateCommandForBlock(routedMessage, candidateHeight),
                routedMessage);
    }

    @Override
    public void apply(AppBlock routedBlock, CompositeWorkflowContext context) {
        if (directAuthorizer == null) {
            map.applyCommands(routedBlock, context.state(mapGeneration));
            return;
        }
        map.applyCommands(routedBlock, context.state(mapGeneration),
                (message, command, mapState) -> {
                    AuthenticatedMapDirectAuthorizer.AuthorizationResult result =
                            directAuthorizer.authorize(command, routedBlock.height(),
                                    message.getMessageId(), context.state(actorsGeneration),
                                    context.state(approvalsGeneration), mapState);
                    return new AuthenticatedMapStateMachine.FinalAuthorization(
                            result.errorCode(), result.governedMutationIndexes(),
                            result.consumptions(), result.approvalConsumptions());
                });
    }

    private static AppStateMachine.AdmissionResult validateActorSignatures(
            AppStateMachine.AdmissionResult structural,
            AppMessage message
    ) {
        if (!structural.isAccepted()) return structural;
        try {
            var command = AuthenticatedMapAuthorizationContract.decodeCommand(
                    message.getBody());
            boolean invalid = command.evidence().stream()
                    .filter(MapActorAuthorizationV1.class::isInstance)
                    .map(MapActorAuthorizationV1.class::cast)
                    .anyMatch(authorization -> !authorization.verifyClaimedKey());
            return invalid
                    ? AppStateMachine.AdmissionResult.reject("INVALID_SIGNATURE")
                    : structural;
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_PAYLOAD");
        }
    }
}
