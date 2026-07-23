package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
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
 * Adapter for an {@link AppStateMachine} that can operate on a component-local
 * state view and a routed block. The composite supplies only the component's
 * messages and recomputes the routed block's {@code messagesRoot}; delegates
 * must not assume that the block represents the complete chain message batch
 * or that its {@code stateRoot} is a component-local post-state commitment.
 * Its finality certificate is empty because the full-block certificate cannot
 * authenticate this synthetic projection.
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
    public void apply(AppBlock routedBlock, AppStateWriter ownState, AppEffectEmitter ownedEffects) {
        delegate.apply(routedBlock, ownState, ownedEffects);
    }

    @Override
    public void onEffectResult(
            AppBlock block,
            EffectResult result,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    ) {
        delegate.onEffectResult(block, result, ownState, ownedEffects);
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        return delegate.query(queryRoutes.getOrDefault(localPath, localPath), params, ownState);
    }
}
