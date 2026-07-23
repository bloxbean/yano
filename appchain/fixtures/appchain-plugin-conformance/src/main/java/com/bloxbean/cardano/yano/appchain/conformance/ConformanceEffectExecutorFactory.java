package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
            public Set<String> effectTypes() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "effect-executor type declaration");
                return Set.of("conformance.effect");
            }

            @Override
            public EffectExecutorOperationalSnapshot operationalSnapshot() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "effect-executor operational snapshot");
                return new EffectExecutorOperationalSnapshot(
                        EffectExecutorOperationalSnapshot.Readiness.READY,
                        1, 1, 0, 0, 0,
                        EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE,
                        EffectExecutorOperationalSnapshot.AgeBucket.NEVER,
                        EffectExecutorOperationalSnapshot.FailureCode.NONE);
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
