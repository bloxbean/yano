package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
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
 * The execution context retains the original globally authenticated block
 * identity while exposing only this component's routed messages.
 */
public interface CompositeComponent {
    ComponentDescriptor descriptor();

    default void init(AppStateReader ownState, AppChainInfo chain) {
    }

    default AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        return AppStateMachine.AdmissionResult.accept();
    }

    default AppStateMachine.AdmissionResult validateForBlock(
            AppMessage routedMessage,
            long candidateHeight,
            AppStateReader ownState
    ) {
        return validate(routedMessage);
    }

    void apply(
            AppBlockExecutionContext context,
            AppStateWriter ownState,
            AppEffectEmitter ownedEffects
    );

    default void onEffectResult(
            AppBlockExecutionContext context,
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
