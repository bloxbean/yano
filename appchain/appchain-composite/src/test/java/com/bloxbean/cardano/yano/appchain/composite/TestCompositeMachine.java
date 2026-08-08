package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;

/** Test fixture pairing a profile descriptor with an ordinary state machine. */
interface TestCompositeMachine extends AppStateMachine {
    ComponentDescriptor descriptor();

    @Override
    default String id() {
        return descriptor().componentId();
    }
}
