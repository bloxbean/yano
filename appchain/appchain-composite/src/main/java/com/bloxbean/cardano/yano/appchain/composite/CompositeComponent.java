package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;

/**
 * Bundle-local deterministic component executed through enforced composite views.
 * The block supplied to {@link #apply} is a routed projection: its messages and
 * {@code messagesRoot} contain only this component's admitted messages, its
 * {@code stateRoot} is inherited metadata rather than a component-local
 * post-state commitment, and its finality certificate is deliberately empty.
 * A certificate over the original full block cannot authenticate the synthetic
 * projection and must never be verified against it. {@link #onEffectResult}
 * receives an even narrower metadata-only projection with zero messages and
 * an empty certificate, so one component cannot inspect sibling traffic while
 * incorporating its owned result.
 */
public interface CompositeComponent {
    ComponentDescriptor descriptor();

    default void init(AppStateReader ownState, AppChainInfo chain) {
    }

    default AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return AppStateMachine.AdmissionResult.accept();
    }

    void apply(AppBlock routedBlock, AppStateWriter ownState, AppEffectEmitter ownedEffects);

    default void onEffectResult(
            AppBlock block,
            EffectResult result,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    ) {
    }

    default byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "component query is not supported: " + descriptor().componentId());
    }
}
