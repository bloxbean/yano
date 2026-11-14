package org.yanoproject.runtime.assembly;

import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.DevnetControl;
import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.NodeLifecycle;
import org.yanoproject.api.ProducerControl;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.TxGateway;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppChainGateway;
import org.yanoproject.api.appchain.AppChainGateways;
import org.yanoproject.api.appchain.AppLedgerOperatingProfile;
import org.yanoproject.api.config.NodeConfig;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.listener.NodeEventListener;
import org.yanoproject.api.model.NodeStatus;
import org.yanoproject.api.plugin.PluginCatalogView;
import org.yanoproject.api.plugin.domain.DomainApiGateway;
import org.yanoproject.api.plugin.operations.PluginOperationsView;
import org.yanoproject.appchain.config.AppChainConfigParser;
import org.yanoproject.runtime.appchain.AppChainManager;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** App-only composition of the existing kernel, catalog, transport and app-chain manager. */
final class AppLedgerAssembly implements Yano, NodeLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AppLedgerAssembly.class);
    private final RuntimeMaintenanceGate maintenance = new RuntimeMaintenanceGate();
    private final YanoConfig config;
    private final NodeKernel kernel;
    private final AppChainManager chains;
    private final ServeSubsystem server;
    private final PluginRuntimeEnvironment plugins;
    private final PluginOperationsRegistry operations;
    private final DomainApiRegistry domainApis;

    AppLedgerAssembly(YanoConfig source, RuntimeOptions options, PluginLoaderHandle loader) {
        config = source.toBuilder().build();
        Map<String, Object> globals = options.globals();
        validateHost(config, globals);
        List<AppChainConfig> chainConfigs = AppChainConfigParser.parseChains(globals, true);
        chainConfigs.forEach(chain -> AppLedgerOperatingProfile.STANDALONE.validate(chain));
        long magic = Long.parseLong(String.valueOf(globals.get("yano.app-chain.network-magic")));
        Schedulers schedulers = new Schedulers();
        PropagatingEventBus events = new PropagatingEventBus();
        List<AutoCloseable> owned = new ArrayList<>();
        owned.add(schedulers);
        owned.add(events);
        try {
            plugins = PluginRuntimeEnvironment.open(options.plugins(), loader != null ? loader
                    : PluginLoaderHandle.classpath(Thread.currentThread().getContextClassLoader()));
            owned.add(plugins);
            operations = new PluginOperationsRegistry(plugins, log);
            owned.add(operations);
            PluginManager manager = options.plugins().enabled()
                    ? plugins.createNodePluginManager(events, schedulers.scheduled(), operations) : null;
            if (manager != null) {
                owned.add(manager);
                manager.discoverAndInit();
            }
            List<AppChainSubsystem> subsystems = new ArrayList<>();
            for (AppChainConfig chain : chainConfigs) {
                AppChainSubsystem subsystem = new AppChainSubsystem(chain, magic, events, null,
                        config.getAppChainStoragePath(), plugins.classLoader(), plugins.providers(), log);
                owned.add(subsystem);
                subsystems.add(subsystem);
            }
            chains = new AppChainManager(subsystems, log);
            server = ServeSubsystem.appOnly(config.getServerPort(), magic, log);
            owned.add(server);
            server.enableAppLayer(chains.serverAgentFactories());
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
            if (manager != null) stages.add(new Subsystem() {
                public String name() { return "plugins"; }
                public void start() { manager.startAll(); }
                public void stop() { manager.stopAll(); }
            });
            stages.add(chains);
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

    private static void validateHost(YanoConfig config, Map<String, Object> globals) {
        if (!Boolean.parseBoolean(String.valueOf(globals.get("yano.app-chain.enabled")))) {
            throw new IllegalArgumentException("yano.mode=app-ledger requires yano.app-chain.enabled=true");
        }
        if (!"dedicated".equals(String.valueOf(globals.get("yano.app-chain.transport.mode")))) {
            throw new IllegalArgumentException("yano.mode=app-ledger requires yano.app-chain.transport.mode=dedicated");
        }
        Object magicValue = globals.get("yano.app-chain.network-magic");
        long magic;
        try {
            magic = Long.parseLong(String.valueOf(magicValue));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Standalone requires yano.app-chain.network-magic (unsigned 32-bit)");
        }
        if (magic <= 2 || magic > 0xffff_ffffL || magic == 764824073 || magic == 1097911063) {
            throw new IllegalArgumentException("yano.app-chain.network-magic must be a distinct app network identity");
        }
        if (config.isEnableBlockProducer() || config.isEnableBootstrap() || config.isDevMode()) {
            throw new IllegalArgumentException("Cardano producer/bootstrap/dev mode is unavailable in yano.mode=app-ledger");
        }
        String path = config.getAppChainStoragePath();
        if (path == null || path.isBlank()) throw new IllegalArgumentException("yano.app-chain.storage.path is required");
        Path appPath = Path.of(path).toAbsolutePath().normalize();
        Path l1Path = Path.of(config.getRocksDBPath() == null ? "chainstate" : config.getRocksDBPath())
                .toAbsolutePath().normalize();
        if (appPath.equals(l1Path)) throw new IllegalArgumentException("App storage must differ from yano.storage.path");
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
    public ChainQuery chain() { return unavailable(ChainQuery.class); }
    public LedgerQuery ledger() { return unavailable(LedgerQuery.class); }
    public TxGateway txGateway() { return unavailable(TxGateway.class); }
    public TxEvaluationGateway txEvaluationGateway() { return unavailable(TxEvaluationGateway.class); }
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
    public void start() { kernel.start(); }
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
