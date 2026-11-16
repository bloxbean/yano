package org.yanoproject.runtime.assembly;

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
import org.yanoproject.api.config.NodeConfig;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.listener.NodeEventListener;
import org.yanoproject.api.model.NodeStatus;
import org.yanoproject.api.plugin.PluginCatalogView;
import org.yanoproject.api.plugin.operations.PluginOperationsView;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.kernel.KernelState;
import org.yanoproject.runtime.maintenance.RuntimeMaintenanceGate;
import org.yanoproject.runtime.kernel.NodeKernel;
import org.yanoproject.runtime.kernel.Schedulers;
import org.yanoproject.runtime.kernel.ServiceRegistry;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;
import org.yanoproject.runtime.plugins.PluginManager;
import org.yanoproject.runtime.plugins.PluginOperationsRegistry;
import org.yanoproject.runtime.plugins.PluginRuntimeEnvironment;
import org.yanoproject.runtime.server.ServeSubsystem;
import org.yanoproject.runtime.util.LifecycleFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic host without Cardano chainstate ({@code yano.mode=app-ledger}): the existing kernel, plugin
 * catalog, Host API, app-only N2N server and REST. A required plugin supplies the app-only network.
 */
final class AppLedgerAssembly implements Yano, NodeLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AppLedgerAssembly.class);
    private final ChainQuery unavailableChain = unavailable(ChainQuery.class);
    private final LedgerQuery unavailableLedger = unavailable(LedgerQuery.class);
    private final TxGateway unavailableTransactions = unavailable(TxGateway.class);
    private final TxEvaluationGateway unavailableEvaluation = unavailable(TxEvaluationGateway.class);
    private final RuntimeMaintenanceGate maintenance = new RuntimeMaintenanceGate();
    private final YanoConfig config;
    private final NodeKernel kernel;
    private final ServeSubsystem server;
    private final PluginRuntimeEnvironment plugins;
    private final PluginOperationsRegistry operations;
    private final PluginManager manager;
    private final DefaultHostLifecycleRegistry hostLifecycle;

    AppLedgerAssembly(YanoConfig source, RuntimeOptions options, PluginLoaderHandle loader) {
        config = source.toBuilder().build();
        validateHost(config);
        Map<String, Object> globals = options.globals();
        Schedulers schedulers = new Schedulers();
        PropagatingEventBus events = new PropagatingEventBus();
        List<AutoCloseable> owned = new ArrayList<>();
        owned.add(schedulers);
        owned.add(events);
        try {
            plugins = PluginRuntimeEnvironment.open(options.plugins(), loader != null ? loader
                    : PluginLoaderHandle.classpath(Thread.currentThread().getContextClassLoader()),
                    PluginRuntimeEnvironment.requiredBundles(globals));
            owned.add(plugins);
            operations = new PluginOperationsRegistry(plugins, log);
            owned.add(operations);
            DefaultN2NExtensionRegistry n2n = new DefaultN2NExtensionRegistry(false);
            manager = plugins.createNodePluginManager(events, schedulers.scheduled(), operations);
            owned.add(manager);
            hostLifecycle = new DefaultHostLifecycleRegistry(DefaultHostLifecycleRegistry.Callers.of(plugins),
                    operations.recorder());
            owned.add(hostLifecycle);
            manager.registerHostService(HostApi.SERVICE_KEY, new DefaultHostApi(n2n, plugins.providers(),
                    hostLifecycle, config, options, () -> networkName(n2n), plugins::bundleConfig));
            manager.discoverAndInit(hostLifecycle::close);
            hostLifecycle.runAssemblies(plugins.classLoader());
            server = ServeSubsystem.appOnly(config.getServerPort(), appOnlyNetwork(n2n), log);
            owned.add(server);
            n2n.seal();
            server.enableAppLayer(n2n.inboundFactories());
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
                public String name() { return "app-operations"; }
                public void start() {
                    hostLifecycle.resumePluginProducts();
                    operations.activateTelemetry();
                    operations.startSampling();
                }
                public void stop() { operations.sealAndAwait(); hostLifecycle.sealPluginProducts(); }
            });
            if (config.isEnableServer()) stages.add(server);
            kernel = new NodeKernel(stages,
                    new SubsystemContext(events, schedulers, globals, new ServiceRegistry()));
        } catch (Throwable failure) {
            closeAll(owned, failure);
            throw new AssertionError("unreachable");
        }
    }

    private static void validateHost(YanoConfig config) {
        if (config.isEnableBlockProducer() || config.isEnableBootstrap() || config.isDevMode()) {
            throw new IllegalArgumentException(
                    "Cardano producer/bootstrap/dev mode is unavailable in yano.mode=app-ledger");
        }
    }

    private static long appOnlyNetwork(DefaultN2NExtensionRegistry n2n) {
        return n2n.appOnlyNetwork().orElseThrow(() -> new IllegalStateException(
                "app-ledger mode requires a plugin that provides an app-only network"));
    }

    private static String networkName(DefaultN2NExtensionRegistry n2n) {
        return "app-" + n2n.appOnlyNetwork().orElseThrow(() -> new IllegalStateException(
                "The app-only network is not known before a plugin calls requireAppOnlyNetwork"));
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
    @Override public <T> Optional<T> service(String key, Class<T> type) { return manager.pluginService(key, type); }
    public Optional<PluginCatalogView> pluginCatalog() { return Optional.of(plugins.catalog()); }
    public Optional<PluginOperationsView> pluginOperations() { return Optional.of(operations); }
    public void start() {
        kernel.start(hostLifecycle::restartReady);
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
