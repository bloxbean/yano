package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless state-machine fixture used only by packaged plugin conformance checks. */
public final class ConformanceStateMachineProvider implements AppStateMachineProvider {
    public static final String ID = "conformance-machine";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppStateMachine create() {
        AppStateMachine machine = new AppStateMachine() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public String id() {
                ConformanceTcclProbe.requireCatalogFacade("state-machine identity");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine identity");
                return ID;
            }

            @Override
            public void init(com.bloxbean.cardano.yano.api.appchain.AppStateReader state,
                             com.bloxbean.cardano.yano.api.appchain.AppChainInfo info) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine initialization");
            }

            @Override
            public void apply(AppBlock block, AppStateWriter writer) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine apply");
                // The isolated conformance chain never proposes a block.
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return machine;
    }
}
