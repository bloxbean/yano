package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless effect-executor fixture used only by the native plugin conformance build. */
public final class ConformanceEffectExecutorFactory implements AppEffectExecutorFactory {
    public static final String SCHEME = "conformance-effect";
    public static final String EXECUTOR_ID = "conformance-effect-executor";

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        List<AppEffectExecutor> executors = List.of(new AppEffectExecutor() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public String id() {
                ConformanceTcclProbe.requireCatalogFacade("effect-executor identity");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "effect-executor identity");
                return EXECUTOR_ID;
            }

            @Override
            public boolean supports(String effectType) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "effect-executor routing");
                return false;
            }

            @Override
            public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "effect-executor execution");
                return EffectExecution.confirmed(new byte[0]);
            }

            @Override
            public void close() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "effect-executor close");
            }
        });
        ConformanceTcclProbe.poisonProviderCallback();
        return executors;
    }
}
