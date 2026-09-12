package org.yanoproject.runtime.assembly;

import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.DevnetControl;
import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.MempoolQueryGateway;
import org.yanoproject.api.MempoolAdminGateway;
import org.yanoproject.api.NodeLifecycle;
import org.yanoproject.api.ProducerControl;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.TxGateway;
import org.yanoproject.api.config.NodeConfig;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.listener.NodeEventListener;
import org.yanoproject.api.model.NodePeers;
import org.yanoproject.api.model.NodeStatus;
import org.yanoproject.runtime.debug.DebugLedgerStateAccess;
import org.yanoproject.runtime.devnet.spi.DevnetRuntime;
import org.yanoproject.runtime.devnet.spi.DevnetRuntimeProvider;
import org.yanoproject.runtime.kernel.NodeKernel;
import org.yanoproject.runtime.kernel.RuntimeKernelProvider;
import org.yanoproject.runtime.kernel.Schedulers;
import org.yanoproject.runtime.kernel.ServiceRegistry;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.runtime.kernel.SubsystemHealth;
import org.yanoproject.runtime.internal.RuntimeNode;
import org.yanoproject.runtime.maintenance.RuntimeMaintenanceGate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link Yano} implementation returned by {@link YanoAssembly}.
 *
 * <p>This type binds the role-specific API facets to a lifecycle backed by the
 * runtime kernel.</p>
 */
final class RuntimeYano implements Yano, DevnetRuntimeProvider {
    private final NodeLifecycle nodeLifecycle;
    private final ChainQuery chainQuery;
    private final LedgerQuery ledgerQuery;
    private final TxGateway txGateway;
    private final TxEvaluationGateway txEvaluationGateway;
    private final ProducerControl producerControl;
    private final RuntimeMaintenanceGate maintenanceGate;
    private final DebugLedgerStateAccess debugLedgerStateAccess;
    private final AutoCloseable closeable;
    private final YanoAssembly.Role role;
    private final NodeKernel kernel;
    private final NodeLifecycle lifecycle;

    RuntimeYano(NodeLifecycle nodeLifecycle,
                ChainQuery chainQuery,
                LedgerQuery ledgerQuery,
                TxGateway txGateway,
                TxEvaluationGateway txEvaluationGateway,
                ProducerControl producerControl,
                RuntimeMaintenanceGate maintenanceGate,
                DebugLedgerStateAccess debugLedgerStateAccess,
                AutoCloseable closeable,
                YanoAssembly.Role role) {
        this(nodeLifecycle,
                chainQuery,
                ledgerQuery,
                txGateway,
                txEvaluationGateway,
                producerControl,
                maintenanceGate,
                debugLedgerStateAccess,
                closeable,
                role,
                new Schedulers());
    }

    RuntimeYano(NodeLifecycle nodeLifecycle,
                ChainQuery chainQuery,
                LedgerQuery ledgerQuery,
                TxGateway txGateway,
                TxEvaluationGateway txEvaluationGateway,
                ProducerControl producerControl,
                RuntimeMaintenanceGate maintenanceGate,
                DebugLedgerStateAccess debugLedgerStateAccess,
                AutoCloseable closeable,
                YanoAssembly.Role role,
                Schedulers schedulers) {
        this.nodeLifecycle = Objects.requireNonNull(nodeLifecycle, "nodeLifecycle");
        this.chainQuery = Objects.requireNonNull(chainQuery, "chainQuery");
        this.ledgerQuery = Objects.requireNonNull(ledgerQuery, "ledgerQuery");
        this.txGateway = Objects.requireNonNull(txGateway, "txGateway");
        this.txEvaluationGateway = Objects.requireNonNull(txEvaluationGateway, "txEvaluationGateway");
        this.producerControl = Objects.requireNonNull(producerControl, "producerControl");
        this.maintenanceGate = Objects.requireNonNull(maintenanceGate, "maintenanceGate");
        this.debugLedgerStateAccess = Objects.requireNonNull(debugLedgerStateAccess, "debugLedgerStateAccess");
        this.closeable = closeable;
        this.role = Objects.requireNonNull(role, "role");
        ServiceRegistry services = new ServiceRegistry();
        services.register(NodeLifecycle.class, nodeLifecycle);
        services.register(ChainQuery.class, chainQuery);
        services.register(LedgerQuery.class, ledgerQuery);
        services.register(TxGateway.class, txGateway);
        services.register(TxEvaluationGateway.class, txEvaluationGateway);
        services.register(ProducerControl.class, producerControl);
        services.register(RuntimeMaintenanceGate.class, maintenanceGate);
        services.register(DebugLedgerStateAccess.class, debugLedgerStateAccess);
        Objects.requireNonNull(schedulers, "schedulers");
        this.kernel = nodeLifecycle instanceof RuntimeKernelProvider provider
                ? provider.kernel()
                : new NodeKernel(
                        List.of(new RuntimeNodeSubsystem(nodeLifecycle, closeable)),
                        new SubsystemContext(null, schedulers, Map.of(), services));
        this.lifecycle = new KernelBackedLifecycle();
    }

    @Override
    public NodeLifecycle lifecycle() {
        return lifecycle;
    }

    @Override
    public ChainQuery chain() {
        return chainQuery;
    }

    @Override
    public boolean installArchiveIngestHold(java.util.function.BooleanSupplier hold, String reason) {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                && node.installArchiveIngestHold(hold, reason);
    }

    @Override
    public boolean markPointerIndexFromGenesis() {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                && node.markPointerIndexFromGenesis();
    }

    @Override
    public org.yanoproject.api.genesis.GenesisUtxoProvider genesisUtxoProvider() {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                ? node.genesisUtxoProvider()
                : org.yanoproject.api.genesis.GenesisUtxoProvider.EMPTY;
    }

    @Override
    public boolean installEpochArtifactContributor(
            org.yanoproject.api.archive.EpochArtifactContributor contributor) {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                && node.installEpochArtifactContributor(contributor);
    }

    @Override
    public java.util.Optional<org.yanoproject.ledgerstate.DefaultAccountStateStore>
            accountStateStoreForArtifacts() {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                ? node.accountStateStoreForArtifacts()
                : java.util.Optional.empty();
    }

    @Override
    public org.yanoproject.api.archive.SnapshotRetentionClamp snapshotRetentionClamp() {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                ? node.snapshotRetentionClamp()
                : org.yanoproject.api.archive.SnapshotRetentionClamp.NONE;
    }

    @Override
    public long commonRollbackFloorSlot() {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                ? node.commonRollbackFloorSlot()
                : -1L;
    }

    @Override
    public org.yanoproject.api.archive.PointerCredentialSource pointerCredentialSource() {
        return chainQuery instanceof org.yanoproject.runtime.internal.RuntimeNode node
                ? node.pointerCredentialSource()
                : org.yanoproject.api.archive.PointerCredentialSource.NONE;
    }

    @Override
    public boolean installProjectionContributor(
            org.yanoproject.api.archive.CanonicalProjectionContributor contributor) {
        return chainQuery instanceof RuntimeNode node
                && node.installProjectionContributor(contributor);
    }

    @Override
    public java.util.List<String> configuredUtxoStorageFilters() {
        return chainQuery instanceof RuntimeNode node
                ? node.configuredUtxoStorageFilters() : java.util.List.of();
    }

    @Override
    public java.util.Optional<org.yanoproject.api.CanonicalBlockReference>
            canonicalBlockReference(long blockNumber) {
        return chainQuery instanceof RuntimeNode node
                ? node.getCanonicalBlockReference(blockNumber) : java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<org.yanoproject.api.db.RocksDbAccess> chainstateRocksAccess() {
        return chainQuery instanceof RuntimeNode node
                ? node.chainstateRocksAccess() : java.util.Optional.empty();
    }

    @Override
    public LedgerQuery ledger() {
        return ledgerQuery;
    }

    @Override
    public TxGateway txGateway() {
        return txGateway;
    }

    @Override
    public TxEvaluationGateway txEvaluationGateway() {
        return txEvaluationGateway;
    }

    @Override
    public MempoolQueryGateway mempoolQueryGateway() {
        return txEvaluationGateway instanceof MempoolQueryGateway gateway
                ? gateway : MempoolQueryGateway.UNAVAILABLE;
    }

    @Override
    public MempoolAdminGateway mempoolAdminGateway() {
        return txGateway instanceof MempoolAdminGateway gateway
                ? gateway : MempoolAdminGateway.UNAVAILABLE;
    }

    @Override
    public Optional<ProducerControl> producerControl() {
        if (nodeLifecycle.getConfig() instanceof YanoConfig config && config.isEnableBlockProducer()) {
            return Optional.of(producerControl);
        }
        return Optional.empty();
    }

    @Override
    public Optional<DevnetControl> devnetControl() {
        return Optional.empty();
    }

    @Override
    public Optional<DevnetRuntime> devnetRuntime() {
        if ((role == YanoAssembly.Role.DEVNET
                || role == YanoAssembly.Role.DEVNET_TIME_TRAVEL)
                && nodeLifecycle.getConfig() instanceof YanoConfig config
                && config.isDevMode()
                && config.isEnableBlockProducer()
                && nodeLifecycle instanceof DevnetRuntimeProvider provider) {
            return provider.devnetRuntime();
        }
        return Optional.empty();
    }

    @Override
    public Optional<NodeKernel> kernel() {
        return Optional.of(kernel);
    }

    @Override
    public Optional<org.yanoproject.api.plugin.PluginCatalogView> pluginCatalog() {
        return nodeLifecycle instanceof RuntimeNode runtimeNode
                ? Optional.of(runtimeNode.pluginCatalog()) : Optional.empty();
    }

    @Override
    public Optional<org.yanoproject.api.plugin.operations.PluginOperationsView>
            pluginOperations() {
        return nodeLifecycle instanceof RuntimeNode runtimeNode
                ? Optional.of(runtimeNode.pluginOperations()) : Optional.empty();
    }

    @Override
    public Optional<org.yanoproject.api.appchain.AppChainGateway> appChain() {
        if (nodeLifecycle instanceof org.yanoproject.runtime.internal.RuntimeNode runtimeNode) {
            return Optional.ofNullable(runtimeNode.appChainGateway());
        }
        return Optional.empty();
    }

    @Override
    public org.yanoproject.api.appchain.AppChainGateways appChains() {
        if (nodeLifecycle instanceof org.yanoproject.runtime.internal.RuntimeNode runtimeNode) {
            return runtimeNode.appChainGateways();
        }
        return org.yanoproject.api.appchain.AppChainGateways.empty();
    }

    @Override
    public org.yanoproject.api.plugin.domain.DomainApiGateway domainApis() {
        if (nodeLifecycle instanceof org.yanoproject.runtime.internal.RuntimeNode runtimeNode) {
            return runtimeNode.domainApis();
        }
        return org.yanoproject.api.plugin.domain.DomainApiGateway.empty();
    }

    @Override
    public Optional<org.yanoproject.api.plugin.domain.LocalReadModelHost>
            localReadModels() {
        return nodeLifecycle instanceof RuntimeNode runtimeNode
                ? Optional.of(runtimeNode.localReadModels())
                : Optional.empty();
    }

    @Override
    public Optional<RuntimeMaintenanceGate> maintenanceGate() {
        return Optional.of(maintenanceGate);
    }

    @Override
    public Optional<DebugLedgerStateAccess> debugLedgerStateAccess() {
        return Optional.of(debugLedgerStateAccess);
    }

    @Override
    public Optional<org.yanoproject.api.events.stream.NodeEventStream> eventStream() {
        // The runtime node behind the gateway interfaces also fans out L1 events.
        return txGateway instanceof org.yanoproject.api.events.stream.NodeEventStream stream
                ? Optional.of(stream)
                : Optional.empty();
    }

    @Override
    public void close() {
        if (nodeLifecycle instanceof RuntimeKernelProvider && closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to close Yano node", e);
            }
            return;
        }
        kernel.close();
    }

    /**
     * Lifecycle facade that routes start/stop through the kernel while
     * preserving status reads from the underlying runtime node.
     */
    private final class KernelBackedLifecycle implements NodeLifecycle {
        @Override
        public void start() {
            if (nodeLifecycle instanceof RuntimeKernelProvider) {
                nodeLifecycle.start();
            } else {
                kernel.start();
            }
        }

        @Override
        public void stop() {
            if (nodeLifecycle instanceof RuntimeKernelProvider) {
                nodeLifecycle.stop();
            } else {
                kernel.stop();
            }
        }

        @Override
        public boolean isRunning() {
            return nodeLifecycle.isRunning();
        }

        @Override
        public boolean isSyncing() {
            return nodeLifecycle.isSyncing();
        }

        @Override
        public boolean isServerRunning() {
            return nodeLifecycle.isServerRunning();
        }

        @Override
        public NodeStatus getStatus() {
            return nodeLifecycle.getStatus();
        }

        @Override
        public NodePeers getPeers() {
            return nodeLifecycle.getPeers();
        }

        @Override
        public NodeConfig getConfig() {
            return nodeLifecycle.getConfig();
        }

        @Override
        public void addNodeEventListener(NodeEventListener listener) {
            nodeLifecycle.addNodeEventListener(listener);
        }

        @Override
        public void removeNodeEventListener(NodeEventListener listener) {
            nodeLifecycle.removeNodeEventListener(listener);
        }
    }

    /**
     * Kernel subsystem adapter for the legacy runtime node lifecycle.
     */
    private static final class RuntimeNodeSubsystem implements Subsystem {
        private final NodeLifecycle nodeLifecycle;
        private final AutoCloseable closeable;

        private RuntimeNodeSubsystem(NodeLifecycle nodeLifecycle, AutoCloseable closeable) {
            this.nodeLifecycle = nodeLifecycle;
            this.closeable = closeable;
        }

        @Override
        public String name() {
            return "runtime-node";
        }

        @Override
        public void start() {
            nodeLifecycle.start();
        }

        @Override
        public void stop() {
            nodeLifecycle.stop();
        }

        @Override
        public void close() {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to close Yano node", e);
                }
            } else {
                nodeLifecycle.stop();
            }
        }

        @Override
        public SubsystemHealth health() {
            try {
                NodeStatus status = nodeLifecycle.getStatus();
                if (status != null && status.isRuntimeDegraded()) {
                    return SubsystemHealth.degraded(name(), status.getRuntimeDegradedReason());
                }
                if (status != null && status.isPeerRecoveryTerminal()) {
                    return SubsystemHealth.down(name(), status.getPeerTerminalFailureMessage());
                }
                if (status != null && status.getStatusMessage() != null
                        && status.getStatusMessage().toLowerCase().contains("error")) {
                    return SubsystemHealth.down(name(), status.getStatusMessage());
                }
                return SubsystemHealth.up(name());
            } catch (Exception e) {
                return SubsystemHealth.down(name(), e.toString());
            }
        }
    }
}
