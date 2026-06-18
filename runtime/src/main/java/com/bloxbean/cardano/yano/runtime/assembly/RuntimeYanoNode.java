package com.bloxbean.cardano.yano.runtime.assembly;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.listener.NodeEventListener;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntime;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntimeProvider;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.kernel.RuntimeKernelProvider;
import com.bloxbean.cardano.yano.runtime.kernel.Schedulers;
import com.bloxbean.cardano.yano.runtime.kernel.ServiceRegistry;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemContext;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link YanoNode} implementation returned by {@link YanoAssembly}.
 *
 * <p>This type binds the role-specific API facets to a lifecycle backed by the
 * runtime kernel.</p>
 */
final class RuntimeYanoNode implements YanoNode, DevnetRuntimeProvider {
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

    RuntimeYanoNode(NodeLifecycle nodeLifecycle,
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

    RuntimeYanoNode(NodeLifecycle nodeLifecycle,
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
    public Optional<RuntimeMaintenanceGate> maintenanceGate() {
        return Optional.of(maintenanceGate);
    }

    @Override
    public Optional<DebugLedgerStateAccess> debugLedgerStateAccess() {
        return Optional.of(debugLedgerStateAccess);
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
