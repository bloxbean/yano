package com.bloxbean.cardano.yano.runtime.assembly;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;

import java.util.Optional;

/**
 * Thin handle returned by runtime assembly recipes.
 */
public interface Yano extends AutoCloseable {
    NodeLifecycle lifecycle();

    ChainQuery chain();

    LedgerQuery ledger();

    TxGateway txGateway();

    TxEvaluationGateway txEvaluationGateway();

    Optional<ProducerControl> producerControl();

    Optional<DevnetControl> devnetControl();

    Optional<NodeKernel> kernel();

    /**
     * App-chain gateway (adr/app-layer/005); empty when the app chain is
     * disabled or when multiple chains are hosted (use {@link #appChains()}).
     */
    default Optional<com.bloxbean.cardano.yano.api.appchain.AppChainGateway> appChain() {
        return Optional.empty();
    }

    /** All hosted app chains (adr/app-layer/006 E5.2); empty registry when disabled. */
    default com.bloxbean.cardano.yano.api.appchain.AppChainGateways appChains() {
        return com.bloxbean.cardano.yano.api.appchain.AppChainGateways.empty();
    }

    /** Host-owned ADR-011.3 domain API dispatcher; empty when none are selected. */
    default com.bloxbean.cardano.yano.api.plugin.domain.DomainApiGateway domainApis() {
        return com.bloxbean.cardano.yano.api.plugin.domain.DomainApiGateway.empty();
    }

    default Optional<RuntimeMaintenanceGate> maintenanceGate() {
        return Optional.empty();
    }

    default Optional<DebugLedgerStateAccess> debugLedgerStateAccess() {
        return Optional.empty();
    }

    /** Immutable, secret-free ADR-011.2 plugin catalog inventory. */
    default Optional<com.bloxbean.cardano.yano.api.plugin.PluginCatalogView> pluginCatalog() {
        return Optional.empty();
    }

    default void start() {
        lifecycle().start();
    }

    default void stop() {
        lifecycle().stop();
    }

    @Override
    default void close() {
        stop();
        kernel().ifPresent(NodeKernel::close);
    }
}
