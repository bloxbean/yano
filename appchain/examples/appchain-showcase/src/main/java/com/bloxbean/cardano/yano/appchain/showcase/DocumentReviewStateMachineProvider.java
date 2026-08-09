package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/** Demo provider for document trail + domain actors + role-aware approval composition. */
public final class DocumentReviewStateMachineProvider implements AppStateMachineProvider {
    public static final String ID = "document-review";

    @Override public String id() { return ID; }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("document-review requires app-chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        return DocumentReviewPreset.create(context);
    }
}
