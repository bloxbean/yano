package org.yanoproject.runtime.assembly;

import com.bloxbean.cardano.yaci.core.common.NetworkType;
import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.runtime.host.DefaultHostApi;
import org.yanoproject.runtime.host.DefaultHostLifecycleRegistry;
import org.yanoproject.runtime.host.DefaultN2NExtensionRegistry;
import org.yanoproject.api.DevnetControl;
import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.NodeLifecycle;
import org.yanoproject.api.ProducerControl;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.TxGateway;
import org.yanoproject.api.appchain.AppChainGateway;
import org.yanoproject.api.appchain.AppChainGateways;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.appchain.config.AppChainConfigParser;
import org.yanoproject.api.config.NodeConfig;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.listener.NodeEventListener;
import org.yanoproject.api.model.NodeStatus;
import org.yanoproject.api.plugin.PluginCatalogView;
import org.yanoproject.api.plugin.domain.DomainApiGateway;
import org.yanoproject.api.plugin.operations.PluginOperationsView;
import org.yanoproject.runtime.appchain.AppLedgerManager;
import org.yanoproject.x.framework.host.AppLedgerHostPlugin;
import org.yanoproject.api.config.PluginConfigValues;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.kernel.KernelState;
import org.yanoproject.runtime.maintenance.RuntimeMaintenanceGate;
import org.yanoproject.runtime.kernel.NodeKernel;
import org.yanoproject.runtime.kernel.Schedulers;
import org.yanoproject.runtime.kernel.ServiceRegistry;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.runtime.plugins.DomainApiRegistry;
import org.yanoproject.runtime.plugins.LocalReadModelRegistry;
import org.yanoproject.runtime.plugins.LocalReadModelContributionRegistry;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;
import org.yanoproject.runtime.plugins.PluginManager;
import org.yanoproject.runtime.plugins.PluginOperationsRegistry;
import org.yanoproject.runtime.plugins.PluginRuntimeEnvironment;
import org.yanoproject.runtime.server.ServeSubsystem;
import org.yanoproject.runtime.util.LifecycleFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** App-only composition of the existing kernel, catalog, transport and app-chain manager. */
final class AppLedgerAssembly implements Yano, NodeLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AppLedgerAssembly.class);
    // Public Cardano networks plus the existing Yano devnet magic (YanoConfig.devnetDefault).
    private static final Set<Long> CARDANO_NETWORK_MAGICS = Stream.concat(
            Stream.of(NetworkType.values()).map(NetworkType::getProtocolMagic),
            Stream.of(42L)).collect(Collectors.toUnmodifiableSet());
    private final ChainQuery unavailableChain = unavailable(ChainQuery.class);
    private final LedgerQuery unavailableLedger = unavailable(LedgerQuery.class);
    private final TxGateway unavailableTransactions = unavailable(TxGateway.class);
    private final TxEvaluationGateway unavailableEvaluation = unavailable(TxEvaluationGateway.class);
    private final RuntimeMaintenanceGate maintenance = new RuntimeMaintenanceGate();
    private final YanoConfig config;
    private final NodeKernel kernel;
    private final AppLedgerManager chains;
    private final ServeSubsystem server;
    private final PluginRuntimeEnvironment plugins;
    private final PluginOperationsRegistry operations;
    private final DomainApiRegistry domainApis;

    AppLedgerAssembly(YanoConfig source, RuntimeOptions options, PluginLoaderHandle loader) {
        config = source.toBuilder().build();
        Map<String, Object> globals = options.globals();
        long magic = validateHost(config, globals);
        Schedulers schedulers = new Schedulers();
        PropagatingEventBus events = new PropagatingEventBus();
        List<AutoCloseable> owned = new ArrayList<>();
        owned.add(schedulers);
        owned.add(events);
        try {
            plugins = PluginRuntimeEnvironment.open(options.plugins(), loader != null ? loader
                    : PluginLoaderHandle.classpath(Thread.currentThread().getContextClassLoader()),
                    Set.of(AppLedgerHostPlugin.BUNDLE_ID));
            owned.add(plugins);
            operations = new PluginOperationsRegistry(plugins, log);
            owned.add(operations);
            DefaultN2NExtensionRegistry n2n = new DefaultN2NExtensionRegistry(false);
            PluginManager manager = plugins.createNodePluginManager(events, schedulers.scheduled(), operations);
            owned.add(manager);
            DefaultHostLifecycleRegistry hostLifecycle = new DefaultHostLifecycleRegistry();
            owned.add(hostLifecycle);
            manager.registerHostService(HostApi.SERVICE_KEY,
                    new DefaultHostApi(n2n, plugins.providers(), hostLifecycle));
            manager.registerHostService(AppLedgerHostPlugin.NODE_CONFIG_SERVICE, config.toBuilder().build());
            manager.registerHostService(AppLedgerHostPlugin.RUNTIME_OPTIONS_SERVICE,
                    new RuntimeOptions(options.events(), options.plugins(), PluginConfigValues.immutableCopy(globals)));
            manager.discoverAndInit(hostLifecycle::close);
            chains = manager.getService(AppLedgerHostPlugin.ASSEMBLY_SERVICE, AppLedgerHostPlugin.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "Required application-ledger framework did not initialize"))
                    .assemble();
            server = ServeSubsystem.appOnly(config.getServerPort(), magic, log);
            owned.add(server);
            n2n.seal();
            server.enableAppLayer(n2n.inboundFactories());
            LocalReadModelRegistry readModels = new LocalReadModelRegistry();
            owned.add(readModels);
            LocalReadModelContributionRegistry contributions = new LocalReadModelContributionRegistry(
                    plugins, "app-" + magic, chains, readModels);
            owned.add(contributions);
            domainApis = new DomainApiRegistry(plugins, chains, log, readModels, operations);
            owned.add(domainApis);
            List<AutoCloseable> resources = List.copyOf(owned);
            List<Subsystem> stages = new ArrayList<>();
            stages.add(new Subsystem() {
                public String name() { return "app-host-resources"; }
                public void start() { plugins.resumeContributionCallbacks(); }
                public void stop() { plugins.sealContributionCallbacks(); }
                public void close() { closeAll(resources, null); }
            });
            stages.add(new Subsystem() {
                public String name() { return "plugins"; }
                public void start() { manager.startAll(); }
                public void stop() { manager.stopAll(); }
            });
            hostLifecycle.seal();
            stages.addAll(hostLifecycle.subsystems());
            stages.add(new Subsystem() {
                public String name() { return "local-read-models"; }
                public void start() { contributions.resume(); }
                public void stop() { contributions.sealAndAwait(); }
            });
            stages.add(new Subsystem() {
                public String name() { return "app-operations"; }
                public void start() { domainApis.resume(); operations.activateTelemetry(); operations.startSampling(); }
                public void stop() { operations.sealAndAwait(); domainApis.sealAndAwait(); }
            });
            if (config.isEnableServer()) stages.add(server);
            kernel = new NodeKernel(stages,
                    new SubsystemContext(events, schedulers, globals, new ServiceRegistry()));
        } catch (Throwable failure) {
            closeAll(owned, failure);
            throw new AssertionError("unreachable");
        }
    }

    private static long validateHost(YanoConfig config, Map<String, Object> globals) {
        if (!Boolean.parseBoolean(String.valueOf(globals.get("yano.app-chain.enabled")))) {
            throw new IllegalArgumentException("yano.mode=app-ledger requires yano.app-chain.enabled=true");
        }
        Object magicValue = globals.get("yano.app-chain.network-magic");
        long magic;
        try {
            magic = Long.parseLong(String.valueOf(magicValue).trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Standalone requires yano.app-chain.network-magic (unsigned 32-bit)");
        }
        if (magic <= 0 || magic > 0xffff_ffffL || CARDANO_NETWORK_MAGICS.contains(magic)) {
            throw new IllegalArgumentException("yano.app-chain.network-magic must be a distinct app network identity");
        }
        if (config.isEnableBlockProducer() || config.isEnableBootstrap() || config.isDevMode()) {
            throw new IllegalArgumentException(
                    "Cardano producer/bootstrap/dev mode is unavailable in yano.mode=app-ledger");
        }
        String path = config.getAppChainStoragePath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("yano.app-chain.storage.path is required");
        }
        Path appPath = Path.of(path).toAbsolutePath().normalize();
        Path l1Path = Path.of(config.getRocksDBPath() == null ? "chainstate" : config.getRocksDBPath())
                .toAbsolutePath().normalize();
        if (appPath.equals(l1Path)) {
            throw new IllegalArgumentException("App storage must differ from yano.storage.path");
        }
        // Parse through the framework's existing configuration boundary, but reject
        // unavailable host transport before discovering or initializing any plugin.
        for (AppChainConfig chain : AppChainConfigParser.parseChains(globals, true)) {
            Object configuredTransport = chain.pluginSettings().get("transport.mode");
            String transport = configuredTransport == null ? "" : String.valueOf(configuredTransport).trim();
            if (transport.isEmpty()) {
                Object global = globals.get("yano.app-chain.transport.mode");
                transport = global == null ? "" : String.valueOf(global).trim();
            }
            if (!"dedicated".equalsIgnoreCase(transport)) {
                throw new IllegalArgumentException("Standalone app chain '" + chain.chainId()
                        + "' requires transport.mode=dedicated (effective: "
                        + (transport.isEmpty() ? "shared" : transport) + ")");
            }
        }
        return magic;
    }

    private static void closeAll(List<AutoCloseable> resources, Throwable failure) {
        Throwable outcome = failure;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); }
            catch (Throwable cleanup) {
                outcome = LifecycleFailures.merge(outcome, cleanup);
            }
        }
        if (outcome instanceof Error error) throw error;
        if (outcome instanceof RuntimeException exception) throw exception;
        if (outcome != null) throw new IllegalStateException("App-ledger lifecycle failed", outcome);
    }

    /** Preserve injectable facade types while making every Cardano operation explicitly unavailable. */
    private static <T> T unavailable(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Unavailable Cardano capability: " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method);
                };
            }
            throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName()
                    + " requires Cardano L1; yano.mode=app-ledger");
        }));
    }

    public Optional<RuntimeMaintenanceGate> maintenanceGate() { return Optional.of(maintenance); }
    public NodeLifecycle lifecycle() { return this; }
    public ChainQuery chain() { return unavailableChain; }
    public LedgerQuery ledger() { return unavailableLedger; }
    public TxGateway txGateway() { return unavailableTransactions; }
    public TxEvaluationGateway txEvaluationGateway() { return unavailableEvaluation; }
    public Optional<ProducerControl> producerControl() { return Optional.empty(); }
    public Optional<DevnetControl> devnetControl() { return Optional.empty(); }
    public Optional<NodeKernel> kernel() { return Optional.of(kernel); }
    public AppChainGateways appChains() { return chains; }
    public Optional<AppChainGateway> appChain() {
        return chains.all().size() == 1 ? Optional.of(chains.all().iterator().next()) : Optional.empty();
    }
    public DomainApiGateway domainApis() { return domainApis; }
    public Optional<PluginCatalogView> pluginCatalog() { return Optional.of(plugins.catalog()); }
    public Optional<PluginOperationsView> pluginOperations() { return Optional.of(operations); }
    public void start() {
        kernel.start(() -> {
            if (!chains.awaitStopped(Duration.ofSeconds(3))) {
                throw new IllegalStateException(
                        "Application ledgers are still draining; retry start after shutdown completes");
            }
        });
    }
    public void stop() { kernel.stop(); }
    public void close() { kernel.close(); }
    public boolean isRunning() { return kernel.state() == KernelState.RUNNING; }
    public boolean isSyncing() { return false; }
    public boolean isServerRunning() { return server.isRunning(); }
    public NodeConfig getConfig() { return config; }
    public NodeStatus getStatus() {
        return NodeStatus.builder().running(isRunning()).serverRunning(isServerRunning())
                .syncMode("app-ledger").statusMessage("Standalone application ledger; Cardano L1 unavailable").build();
    }
    public void addNodeEventListener(NodeEventListener listener) {
        throw new UnsupportedOperationException("Cardano node events are unavailable in yano.mode=app-ledger");
    }
    public void removeNodeEventListener(NodeEventListener listener) {
        throw new UnsupportedOperationException("Cardano node events are unavailable in yano.mode=app-ledger");
    }
}
