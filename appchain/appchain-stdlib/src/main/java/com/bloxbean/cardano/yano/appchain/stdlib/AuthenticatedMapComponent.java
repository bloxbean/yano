package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.List;
import java.util.Objects;

/** Isolated state/query owner for the authenticated-map composite assembly. */
public final class AuthenticatedMapComponent implements CompositeComponent {
    public static final String COMPONENT_ID = AuthenticatedMapContract.STATE_MACHINE_ID;

    private static final List<String> QUERY_PATHS = List.of(
            AuthenticatedMapContract.POINT_QUERY_PATH,
            AuthenticatedMapContract.RECEIPT_QUERY_PATH);

    private final ComponentDescriptor descriptor;
    private final AuthenticatedMapStateMachine transitions;

    public AuthenticatedMapComponent(
            ComponentDescriptor descriptor,
            AuthenticatedMapStateMachine transitions
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        if (!COMPONENT_ID.equals(descriptor.componentId())
                || !descriptor.topics().isEmpty()
                || !QUERY_PATHS.equals(descriptor.queryPaths())) {
            throw new IllegalArgumentException("invalid authenticated-map component descriptor");
        }
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void init(AppStateReader ownState, AppChainInfo chain) {
        transitions.init(ownState, chain);
    }

    @Override
    public void apply(
            AppBlock routedBlock,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    ) {
        // The authenticated-map workflow exclusively owns command execution.
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
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

    void applyCommands(AppBlock block, AppStateWriter ownState) {
        transitions.applyFinal(block, ownState);
    }

    AuthenticatedMapContract.Genesis genesis() {
        return transitions.genesis();
    }
}
