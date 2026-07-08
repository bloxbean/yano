package com.bloxbean.cardano.yano.devnet;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.bootstrap.BootstrapDataProvider;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntime;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntimeProvider;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapOptions;
import com.bloxbean.cardano.yano.runtime.tx.TransactionServicesFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Devnet composition helper that decorates runtime assemblies with the optional
 * {@link DevnetControl} role.
 */
public final class YanoDevnetAssembly {
    private YanoDevnetAssembly() {
    }

    /**
     * Creates a devnet builder with devnet controls.
     *
     * @param config devnet runtime configuration
     * @return toolkit-aware builder
     */
    public static Builder devnet(YanoConfig config) {
        return new Builder(YanoAssembly.devnet(config), true);
    }

    /**
     * Creates a past-time-travel devnet builder with devnet controls.
     *
     * @param config devnet runtime configuration
     * @return toolkit-aware builder
     */
    public static Builder devnetTimeTravel(YanoConfig config) {
        return new Builder(YanoAssembly.devnetTimeTravel(config), true);
    }

    /**
     * Selects the same runtime recipe as {@link YanoAssembly#fromConfig(YanoConfig)}
     * and adds devnet controls only when that recipe is devnet-capable.
     *
     * @param config runtime configuration
     * @return toolkit-aware builder
     */
    public static Builder fromConfig(YanoConfig config) {
        Objects.requireNonNull(config, "config");
        boolean decorateDevnetControl = config.isDevMode()
                && config.isEnableBlockProducer()
                && !config.isSlotLeaderMode();
        return new Builder(YanoAssembly.fromConfig(config), decorateDevnetControl);
    }

    /**
     * Mutable builder that mirrors the runtime assembly builder and decorates
     * the result with {@link DevnetControl} when appropriate.
     */
    public static final class Builder {
        private final YanoAssembly.Builder delegate;
        private final boolean decorateDevnetControl;

        private Builder(YanoAssembly.Builder delegate, boolean decorateDevnetControl) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.decorateDevnetControl = decorateDevnetControl;
        }

        /**
         * Applies runtime options to the delegated runtime builder.
         *
         * @param runtimeOptions runtime options
         * @return this builder
         */
        public Builder runtimeOptions(RuntimeOptions runtimeOptions) {
            delegate.runtimeOptions(runtimeOptions);
            return this;
        }

        /**
         * Configures in-memory genesis for devnet recipes.
         *
         * @param inMemoryGenesis in-memory genesis data
         * @return this builder
         */
        public Builder inMemoryGenesis(InMemoryDevnetGenesis inMemoryGenesis) {
            delegate.inMemoryGenesis(inMemoryGenesis);
            return this;
        }

        /**
         * Configures a bootstrap provider.
         *
         * @param bootstrapDataProvider bootstrap provider
         * @return this builder
         */
        public Builder bootstrapDataProvider(BootstrapDataProvider bootstrapDataProvider) {
            delegate.bootstrapDataProvider(bootstrapDataProvider);
            return this;
        }

        /**
         * Configures ad hoc rollback before startup.
         *
         * @param rollbackToSlot target slot, or negative to ignore
         * @param rollbackToEpoch target epoch, or negative to ignore
         * @return this builder
         */
        public Builder adhocRollback(long rollbackToSlot, int rollbackToEpoch) {
            delegate.adhocRollback(rollbackToSlot, rollbackToEpoch);
            return this;
        }

        /**
         * Configures optional transaction validation/evaluation services.
         *
         * @param options bootstrap options
         * @param servicesFactory service factory
         * @return this builder
         */
        public Builder transactionBootstrap(TransactionBootstrapOptions options,
                                            TransactionServicesFactory servicesFactory) {
            delegate.transactionBootstrap(options, servicesFactory);
            return this;
        }

        /**
         * Builds the node and installs devnet controls when the selected recipe
         * supports them.
         *
         * @return assembled node
         */
        public Yano build() {
            Yano node = delegate.build();
            if (!decorateDevnetControl) {
                return node;
            }

            DevnetRuntime runtime = devnetRuntime(node)
                    .orElseThrow(() -> new IllegalStateException(
                            "Devnet runtime SPI unavailable for toolkit assembly"));
            return new DevnetYano(node, DevnetToolkit.from(runtime));
        }

        private Optional<DevnetRuntime> devnetRuntime(Yano node) {
            if (node instanceof DevnetRuntimeProvider provider) {
                return provider.devnetRuntime();
            }
            return Optional.empty();
        }
    }

    private static final class DevnetYano implements Yano, DevnetRuntimeProvider {
        private final Yano delegate;
        private final DevnetControl devnetControl;

        private DevnetYano(Yano delegate, DevnetControl devnetControl) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.devnetControl = Objects.requireNonNull(devnetControl, "devnetControl");
        }

        @Override
        public NodeLifecycle lifecycle() {
            return delegate.lifecycle();
        }

        @Override
        public ChainQuery chain() {
            return delegate.chain();
        }

        @Override
        public LedgerQuery ledger() {
            return delegate.ledger();
        }

        @Override
        public TxGateway txGateway() {
            return delegate.txGateway();
        }

        @Override
        public TxEvaluationGateway txEvaluationGateway() {
            return delegate.txEvaluationGateway();
        }

        @Override
        public Optional<ProducerControl> producerControl() {
            return delegate.producerControl();
        }

        @Override
        public Optional<DevnetControl> devnetControl() {
            return Optional.of(devnetControl);
        }

        @Override
        public Optional<NodeKernel> kernel() {
            return delegate.kernel();
        }

        @Override
        public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainGateway> appChain() {
            return delegate.appChain();
        }

        @Override
        public Optional<RuntimeMaintenanceGate> maintenanceGate() {
            return delegate.maintenanceGate();
        }

        @Override
        public Optional<DebugLedgerStateAccess> debugLedgerStateAccess() {
            return delegate.debugLedgerStateAccess();
        }

        @Override
        public Optional<DevnetRuntime> devnetRuntime() {
            if (delegate instanceof DevnetRuntimeProvider provider) {
                return provider.devnetRuntime();
            }
            return Optional.empty();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
