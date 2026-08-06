package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.yano.api.appchain.effects.AppChainEffectContext;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR-UTXO-009 SP-M6: the wired A2 settlement stack, assembled per chain
 * from {@code effects.executors.eutxo-settlement.*} config and the
 * context-aware factory surface. EVERY member builds and registers the
 * co-sign service (so it answers {@code ~bridge/settlement/sign} requests
 * with its own custody verification); only the {@code owner=true} node
 * (single-owner pinning) additionally gets the settlement executor that
 * drives {@code l1.settlement} effects through the QuickTx pipeline.
 */
public final class EutxoSettlementExecutorFactory
        implements AppEffectExecutorFactory {

    public static final String SCHEME = "eutxo-settlement";

    @Override
    public String scheme() {
        return SCHEME;
    }

    /**
     * Config-only creation cannot wire the node-coupled stack — decline.
     * (The subsystem always invokes the context-aware overload.)
     */
    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        return List.of();
    }

    @Override
    public List<AppEffectExecutor> create(
            String chainId, Map<String, String> config,
            AppChainEffectContext context) {
        if (context == null) {
            return List.of();
        }
        SettlementWiring wiring = SettlementWiring.parse(config);
        SettlementClaimsView claimsView = new SettlementClaimsView(
                (path, request) -> {
                    var result = context.query(path, request);
                    return result == null ? null : result.payload();
                });
        SettlementCosignService cosign = new SettlementCosignService(
                context::diffuse,
                context.memberSigner(),
                context.members(),
                context.threshold(),
                body -> claimsView.verifyProposedBody(
                        body, wiring.vaultAddress()),
                wiring.owner(),
                wiring.roundTimeout());
        context.registerBridgeDiffusionHandler(cosign);
        if (!wiring.owner()) {
            return List.of();
        }

        NodeSettlementBackend backend = new NodeSettlementBackend(
                context::submitTx, context.l1UtxoView());
        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(
                CclNodeAdapters.utxoSupplier(context.l1UtxoView()),
                CclNodeAdapters.protocolParamsSupplier(context.protocolParams()),
                CclNodeAdapters.scriptSupplier(context.l1UtxoView()),
                CclNodeAdapters.transactionProcessor(
                        context::submitTx, context::txEvaluation));
        QuickTxSettlePipeline pipeline = new QuickTxSettlePipeline(
                wiring, claimsView, cosign, backend, quickTxBuilder,
                context.l1UtxoView(), context.members(),
                script(wiring.vaultScriptHex()),
                script(wiring.shardScriptHex()));
        return List.of(new PipelinedSettlementExecutor(
                SCHEME, pipeline, claimsView, backend, new InMemoryJournal()));
    }

    private static PlutusV3Script script(String doubleCborHex) {
        return PlutusV3Script.builder()
                .type("PlutusScriptV3")
                .cborHex(doubleCborHex)
                .build();
    }

    /**
     * Per-node journal: settlement idempotency across retries within a node
     * session; a restart re-derives everything from committed state (the
     * pending-claims re-check makes the journal a cache, not a trust root).
     */
    private static final class InMemoryJournal implements BatchSettlementJournal {
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        @Override
        public Optional<Entry> find(String effectKey) {
            return Optional.ofNullable(entries.get(effectKey));
        }

        @Override
        public void save(Entry entry) {
            entries.put(entry.effectKey(), entry);
        }
    }
}
