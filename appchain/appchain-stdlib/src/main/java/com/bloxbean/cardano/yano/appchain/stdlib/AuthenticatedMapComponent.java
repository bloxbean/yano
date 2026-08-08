package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.List;
import java.util.Objects;

/** Isolated state/query owner for the authenticated-map composite assembly. */
public final class AuthenticatedMapComponent implements AppStateMachine {
    public static final String COMPONENT_ID = AuthenticatedMapContract.STATE_MACHINE_ID;

    private static final List<String> BASIC_QUERY_PATHS = List.of(
            AuthenticatedMapContract.POINT_QUERY_PATH,
            AuthenticatedMapContract.RECEIPT_QUERY_PATH,
            AuthenticatedMapContract.CAPABILITIES_QUERY_PATH).stream().sorted().toList();

    private final ComponentDescriptor descriptor;
    private final AuthenticatedMapStateMachine transitions;

    public AuthenticatedMapComponent(
            ComponentDescriptor descriptor,
            AuthenticatedMapStateMachine transitions
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        List<String> expectedQueryPaths = transitions.genesis().governedGenesis() == null
                ? BASIC_QUERY_PATHS
                : List.of(AuthenticatedMapContract.POINT_QUERY_PATH,
                AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                AuthenticatedMapContract.CAPABILITIES_QUERY_PATH,
                AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH,
                AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH).stream()
                .sorted().toList();
        if (!COMPONENT_ID.equals(descriptor.componentId())
                || !descriptor.topics().isEmpty()
                || !expectedQueryPaths.equals(descriptor.queryPaths())) {
            throw new IllegalArgumentException("invalid authenticated-map component descriptor");
        }
    }

    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String id() {
        return descriptor.componentId();
    }

    @Override
    public void init(AppStateReader ownState, AppChainInfo chain) {
        transitions.init(ownState, chain);
    }

    @Override
    public void apply(
            AppBlockExecutionContext execution,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    ) {
        // The authenticated-map workflow exclusively owns command execution.
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        if (AuthenticatedMapContract.CAPABILITIES_QUERY_PATH.equals(localPath)) {
            if (params == null || params.length != 0) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "authenticated-map capabilities query takes no parameters");
            }
            return AuthenticatedMapContract.encodeGenesis(transitions.genesis());
        }
        if (AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH.equals(localPath)) {
            var query = AuthenticatedMapAuthorizationContract.DirectConsumptionQueryV1
                    .decode(params);
            return ownState.get(AuthenticatedMapContract.directConsumptionKey(
                    query.actorId(), query.authorizationId())).orElse(new byte[0]);
        }
        if (AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH.equals(localPath)) {
            String proposalId;
            try {
                proposalId = new String(
                        params, java.nio.charset.StandardCharsets.US_ASCII);
                RoleWorkflowIdentifiers.id(proposalId, "proposalId");
            } catch (RuntimeException malformed) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "approval-consumption query requires a canonical proposal id");
            }
            return ownState.get(AuthenticatedMapContract.approvalConsumptionKey(
                    proposalId)).orElse(new byte[0]);
        }
        return transitions.query(localPath, params, ownState);
    }

    AppStateMachine.AdmissionResult validateCommand(AppMessage message) {
        return transitions.validateFinal(message);
    }

    AppStateMachine.AdmissionResult validateCommandForBlock(
            AppMessage message,
            long candidateHeight
    ) {
        return transitions.validateFinalForBlock(message, candidateHeight);
    }

    void applyCommands(AppBlockExecutionContext execution, AppStateWriter ownState) {
        transitions.applyFinal(execution, ownState);
    }

    void applyCommands(
            AppBlockExecutionContext execution,
            AppStateWriter ownState,
            AuthenticatedMapStateMachine.FinalAuthorizationEvaluator authorizer
    ) {
        transitions.applyFinal(execution, ownState, authorizer);
    }

    AuthenticatedMapContract.Genesis genesis() {
        return transitions.genesis();
    }
}
