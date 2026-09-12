package org.yanoproject.appchain.conformance;

import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

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
            public void init(org.yanoproject.api.appchain.AppStateReader state,
                             org.yanoproject.api.appchain.AppChainInfo info) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine initialization");
            }

            @Override
            public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                              AppEffectEmitter effects) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine apply");
                // The isolated conformance chain never proposes a block.
            }

            @Override
            public byte[] query(String path, byte[] params, AppQueryContext state) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "state-machine committed query");
                if (!"echo".equals(path)) {
                    throw new UnsupportedOperationException("unsupported conformance query");
                }
                String payload = "echo:" + state.committedHeight() + ":"
                        + HexFormat.of().formatHex(params) + ":"
                        + HexFormat.of().formatHex(state.stateRoot());
                return payload.getBytes(StandardCharsets.UTF_8);
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return machine;
    }
}
