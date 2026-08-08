package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;

import java.util.Objects;
import java.util.Map;

/**
 * Adapter for an {@link AppStateMachine} operating on a component-local state
 * view and a restricted view of the original block execution context.
 */
public final class StateMachineComponentAdapter implements CompositeComponent {
    private final ComponentDescriptor descriptor;
    private final AppStateMachine delegate;
    private final Map<String, String> queryRoutes;

    public StateMachineComponentAdapter(ComponentDescriptor descriptor, AppStateMachine delegate) {
        this(descriptor, delegate, Map.of());
    }

    public StateMachineComponentAdapter(
            ComponentDescriptor descriptor,
            AppStateMachine delegate,
            Map<String, String> queryRoutes
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.queryRoutes = Map.copyOf(Objects.requireNonNull(queryRoutes, "queryRoutes"));
        if (!descriptor.queryPaths().containsAll(this.queryRoutes.keySet())) {
            throw new IllegalArgumentException("query route mapping contains an undeclared local path");
        }
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void init(AppStateReader ownState, AppChainInfo chain) {
        delegate.init(ownState, chain);
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return delegate.validate(routedMessage);
    }

    @Override
    public AppStateMachine.AdmissionResult validateForBlock(
            AppMessage routedMessage,
            long candidateHeight,
            AppStateReader ownState
    ) {
        return delegate.validateForBlock(routedMessage, candidateHeight, ownState);
    }

    @Override
    public void apply(
            AppBlockExecutionContext context,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    ) {
        delegate.apply(context, ownState, ownedEffects);
    }

    @Override
    public void onEffectResult(
            AppBlockExecutionContext context,
            EffectResult result,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    ) {
        delegate.onEffectResult(context, result, ownState, ownedEffects);
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        return delegate.query(queryRoutes.getOrDefault(localPath, localPath), params, ownState);
    }
}
