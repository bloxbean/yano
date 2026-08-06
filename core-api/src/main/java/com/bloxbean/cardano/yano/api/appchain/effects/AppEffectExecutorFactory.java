package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.List;
import java.util.Map;

/**
 * ServiceLoader SPI for supplying effect executors without recompiling Yano
 * (ADR app-layer/010 F5), mirroring {@code FinalizedStreamSinkFactory}. Drop a
 * jar with an implementation plus
 * {@code META-INF/services/com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory}
 * into the node's plugins directory; the factory is invoked when its scheme's
 * config sub-map ({@code yano.app-chain.effects.executors.<scheme>.*}) is
 * non-empty.
 */
public interface AppEffectExecutorFactory {

    /** Config namespace selector: {@code effects.executors.<scheme>.*}. */
    String scheme();

    /**
     * Create this factory's executors for a chain. {@code config} is the
     * scheme's sub-map with the prefix stripped. Return an empty list to
     * decline (e.g. required settings missing). Every non-null executor must
     * be a fresh identity owned by this invocation; do not reuse an executor
     * across chains, calls, or factories.
     */
    List<AppEffectExecutor> create(String chainId, Map<String, String> config);

    /**
     * Context-aware creation (ADR-UTXO-009 SP-M6): node-coupled executor
     * stacks (co-sign rounds, L1 views, committed-state queries) override
     * this overload; the host invokes it when building a chain's executors.
     * The default delegates to the config-only form, so existing factories
     * are unaffected. The same contract applies: return an empty list to
     * decline, and never reuse an executor across chains or calls.
     */
    default List<AppEffectExecutor> create(
            String chainId, Map<String, String> config,
            AppChainEffectContext context) {
        return create(chainId, config);
    }
}
