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

    /** Host registration seam for bounded node-local derived read models. */
    default Optional<com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost>
            localReadModels() {
        return Optional.empty();
    }

    /**
     * Shared chainstate RocksDB handles, when the chain state is RocksDB-backed.
     * Used by ADR-039 projection history to place its outbox in the same database as
     * the state its contributors derive projections from.
     */
    default Optional<com.bloxbean.cardano.yano.api.db.RocksDbAccess> chainstateRocksAccess() {
        return Optional.empty();
    }

    /**
     * Install an external hold on canonical ingestion (ADR-039 disk backpressure).
     * Returns false when this runtime has no active sync manager to hold.
     */
    default boolean installArchiveIngestHold(java.util.function.BooleanSupplier hold, String reason) {
        return false;
    }

    /**
     * Record that the as-of pointer index is maintained from genesis. Only valid on an empty
     * chainstate; the store rejects it otherwise.
     */
    default boolean markPointerIndexFromGenesis() {
        return false;
    }

    /**
     * Authoritative pointer-address resolution, owned by the account-state contributor.
     * Empty when this runtime has no such store; callers then treat every pointer as
     * unresolved rather than failing.
     */
    /**
     * Highest slot below which no rollback-capable store can restore state, or {@code -1} when the
     * runtime cannot answer.
     *
     * <p>ADR-039 compares this against the oldest slot an artifact still requires, and pauses
     * rather than acknowledging when the margin is gone. {@code -1} means unknown, which callers
     * must treat as "cannot prove it is safe" rather than as zero - a floor of zero asserts that
     * rollback to genesis is possible, which is the opposite of not knowing.
     */
    default long commonRollbackFloorSlot() {
        return -1L;
    }

    /** The complete normalised genesis distribution; empty when this node has none. */
    default com.bloxbean.cardano.yano.api.genesis.GenesisUtxoProvider genesisUtxoProvider() {
        return com.bloxbean.cardano.yano.api.genesis.GenesisUtxoProvider.EMPTY;
    }

    /** Install the ADR-039 epoch artifact hook; false when no account-state store is present. */
    default boolean installEpochArtifactContributor(
            com.bloxbean.cardano.yano.api.archive.EpochArtifactContributor contributor) {
        return false;
    }

    /**
     * The account-state store, for reading an epoch delegation generation under lease.
     *
     * <p>Exposed concretely rather than behind a narrow interface because the only consumer
     * ({@code EpochSnapshotArtifactReader}) already lives in a module that depends on ledger
     * state; a new interface here would buy no decoupling.
     */
    default java.util.Optional<com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore>
            accountStateStoreForArtifacts() {
        return java.util.Optional.empty();
    }

    /** Snapshot retention clamp, so a referenced generation is not pruned while in use. */
    default com.bloxbean.cardano.yano.api.archive.SnapshotRetentionClamp snapshotRetentionClamp() {
        return com.bloxbean.cardano.yano.api.archive.SnapshotRetentionClamp.NONE;
    }

    default com.bloxbean.cardano.yano.api.archive.PointerCredentialSource pointerCredentialSource() {
        return com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.NONE;
    }

    /**
     * Install the ADR-039 projection contributor so block projection sections are staged
     * inside the contributing subsystem's existing write batch. Returns false when this
     * runtime has no contributing store.
     */
    default boolean installProjectionContributor(
            com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor contributor) {
        return false;
    }

    default Optional<RuntimeMaintenanceGate> maintenanceGate() {
        return Optional.empty();
    }

    default Optional<DebugLedgerStateAccess> debugLedgerStateAccess() {
        return Optional.empty();
    }

    /** L1 event stream for API-layer consumers (SSE, ADR-033 M2). */
    default Optional<com.bloxbean.cardano.yano.api.events.stream.NodeEventStream> eventStream() {
        return Optional.empty();
    }

    /** Immutable, secret-free ADR-011.2 plugin catalog inventory. */
    default Optional<com.bloxbean.cardano.yano.api.plugin.PluginCatalogView> pluginCatalog() {
        return Optional.empty();
    }

    /** Cached ADR-011.4 plugin operations state, when supplied by the runtime. */
    default Optional<com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsView>
            pluginOperations() {
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
