package com.bloxbean.cardano.yano.runtime.assembly;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.bootstrap.BootstrapDataProvider;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntimeProvider;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;
import com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlan;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidationContext;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidationResult;
import com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidator;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapOptions;
import com.bloxbean.cardano.yano.runtime.tx.TransactionServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoAssemblyTest {
    @TempDir
    Path tempDir;

    @Test
    void buildFailureCleanupRunsEveryActionAndPromotesFirstError() {
        RuntimeException primary = new RuntimeException("primary");
        AssertionError fatalCleanup = new AssertionError("fatal cleanup");
        RuntimeException laterCleanup = new RuntimeException("later cleanup");
        AtomicInteger cleanupCalls = new AtomicInteger();

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> YanoAssembly.cleanupBuildFailure(primary,
                        () -> {
                            cleanupCalls.incrementAndGet();
                            throw primary;
                        },
                        () -> {
                            cleanupCalls.incrementAndGet();
                            throw fatalCleanup;
                        },
                        () -> {
                            cleanupCalls.incrementAndGet();
                            throw laterCleanup;
                        }));

        assertSame(fatalCleanup, thrown);
        assertEquals(3, cleanupCalls.get());
        assertEquals(List.of(primary, laterCleanup), List.of(thrown.getSuppressed()));
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void buildFailureCleanupPromotesLaterProcessFatalOverEarlierAssertion() {
        RuntimeException primary = new RuntimeException("primary");
        AssertionError assertion = new AssertionError("assertion");
        TestVirtualMachineError fatal = new TestVirtualMachineError();

        TestVirtualMachineError thrown = assertThrows(TestVirtualMachineError.class,
                () -> YanoAssembly.cleanupBuildFailure(primary,
                        () -> { throw assertion; },
                        () -> { throw fatal; }));

        assertSame(fatal, thrown);
        assertEquals(List.of(assertion), List.of(fatal.getSuppressed()));
        assertEquals(List.of(primary), List.of(assertion.getSuppressed()));
    }

    @Test
    void nonProcessErrorAssemblyFailureClosesRuntimeAndAllowsImmediateRebuild() {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(tempDir.resolve("checked-failure-chainstate").toString());
        AssertionError failure = new AssertionError("transaction bootstrap assertion");

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> YanoAssembly.relay(config)
                        .runtimeOptions(RuntimeOptions.defaults())
                        .transactionBootstrap(
                                TransactionBootstrapOptions.enabled(false, false, "aiken"),
                                (context, options) -> {
                                    throw failure;
                                })
                        .build());

        assertSame(failure, thrown);

        Yano rebuilt = YanoAssembly.relay(config)
                .runtimeOptions(RuntimeOptions.defaults())
                .build();
        rebuilt.close();
    }

    @Test
    void relayRecipeBuildsRoleBasedNode() {
        Yano node = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .runtimeOptions(RuntimeOptions.defaults())
                .build();

        try {
            assertNotNull(node.lifecycle());
            assertNotNull(node.chain());
            assertNotNull(node.ledger());
            assertNotNull(node.txGateway());
            assertNotNull(node.txEvaluationGateway());
            assertTrue(node.maintenanceGate().isPresent());
            assertTrue(node.debugLedgerStateAccess().isPresent());
            assertTrue(node.kernel().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void transactionBootstrapDisabledDoesNotCallFactory() {
        AtomicBoolean called = new AtomicBoolean();
        Yano node = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .transactionBootstrap(TransactionBootstrapOptions.disabled(), (context, options) -> {
                    called.set(true);
                    return Optional.empty();
                })
                .build();

        try {
            assertFalse(called.get());
        } finally {
            node.close();
        }
    }

    @Test
    void bodyValidationBuilderInstallsCustomValidator() {
        AtomicBoolean called = new AtomicBoolean();
        BodyValidator validator = context -> {
            called.set(true);
            return BodyValidationResult.accepted("custom");
        };
        Yano node = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .bodyValidation(v -> v.add(validator))
                .build();

        try {
            Object runtimeNode = field(node, "closeable");
            BodyValidator installed = (BodyValidator) field(runtimeNode, "bodyValidator");
            BodyValidationResult result = installed.validate(new BodyValidationContext(
                    Era.Shelley,
                    Block.builder().build(),
                    List.of(),
                    new byte[] {1},
                    1,
                    1,
                    "abcd"));
            assertTrue(result.accepted());
            assertTrue(called.get());
        } finally {
            node.close();
        }
    }

    @Test
    void unsupportedBodyValidationDefaultPresetFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> YanoAssembly.relay(YanoConfig.serverOnly(0))
                        .bodyValidation(v -> v.useDefault("body-integrity")));
    }

    @Test
    void transactionBootstrapInstallsReturnedScriptEvaluator(@TempDir Path tempDir) {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(tempDir.resolve("chainstate").toString());
        AtomicBoolean called = new AtomicBoolean();

        Yano node = YanoAssembly.relay(config)
                .runtimeOptions(new RuntimeOptions(null, null, Map.of(
                        "yano.utxo.enabled", true,
                        "yano.utxo.prune.schedule.seconds", 60,
                        "yano.metrics.sample.rocksdb.seconds", 0)))
                .transactionBootstrap(TransactionBootstrapOptions.enabled(false, false, "aiken"),
                        (context, options) -> {
                            called.set(true);
                            assertSame(config, context.config());
                            assertNotNull(context.utxoState());
                            assertFalse(options.effectiveEpochParamsTrackingEnabled());
                            assertEquals("aiken", options.scriptEvaluator());
                            return Optional.of(new TransactionServices(null, (txCbor, inputUtxos) -> List.of()));
                        })
                .build();

        try {
            assertTrue(called.get());
            assertTrue(node.txEvaluationGateway().isTransactionEvaluationAvailable());
        } finally {
            node.close();
        }
    }

    @Test
    void assembledRuntimeKernelExposesRuntimeSubsystems() {
        Yano node = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .runtimeOptions(RuntimeOptions.defaults())
                .build();

        try {
            List<String> subsystemNames = node.kernel().orElseThrow().subsystems().stream()
                    .map(com.bloxbean.cardano.yano.runtime.kernel.Subsystem::name)
                    .toList();

            assertEquals(List.of(
                    "runtime-resources",
                    "runtime-startup-boundary",
                    "plugins",
                    "tx",
                    "serve",
                    "runtime-bootstrap-recovery",
                    "utxo",
                    "ledger-state",
                    "chain-storage",
                    "producer",
                    "chronology",
                    "serve-deferred",
                    "sync",
                    "runtime-startup-publication",
                    "runtime-shutdown-boundary"), subsystemNames);
            assertTrue(node.kernel().orElseThrow().health().stream()
                    .allMatch(health -> health.status() == SubsystemHealth.Status.UP));
        } finally {
            node.close();
        }
    }

    @Test
    void transactionBootstrapFactoryFailureDoesNotFailAssembly() {
        Yano node = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .transactionBootstrap(TransactionBootstrapOptions.enabled(false, false, "scalus"),
                        (context, options) -> {
                            throw new IllegalStateException("boom");
                        })
                .build();

        try {
            assertNotNull(node.lifecycle());
            assertFalse(node.txEvaluationGateway().isTransactionEvaluationAvailable());
        } finally {
            node.close();
        }
    }

    @Test
    void bootstrapPartialStatePolicyDisablesDerivedLedgerStateGlobals() {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setEnableBootstrap(true);
        RuntimeOptions options = new RuntimeOptions(null, null, Map.ofEntries(
                Map.entry(YanoPropertyKeys.AccountState.ENABLED, true),
                Map.entry(YanoPropertyKeys.AccountState.STAKE_BALANCE_INDEX_ENABLED, true),
                Map.entry(YanoPropertyKeys.AccountHistory.ENABLED, true),
                Map.entry(YanoPropertyKeys.AccountHistory.TX_EVENTS_ENABLED, true),
                Map.entry(YanoPropertyKeys.AccountHistory.REWARDS_ENABLED, true),
                Map.entry(YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED, true),
                Map.entry(YanoPropertyKeys.Ledger.ADAPOT_ENABLED, true),
                Map.entry(YanoPropertyKeys.Ledger.REWARDS_ENABLED, true),
                Map.entry(YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, true),
                Map.entry(YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED, true),
                Map.entry(YanoPropertyKeys.SnapshotExport.ENABLED, true),
                Map.entry(YanoPropertyKeys.Utxo.ENABLED, true)));

        RuntimeOptions resolved = YanoAssembly.applyBootstrapPartialStatePolicy(config, options);

        assertEquals(false, resolved.globals().get(YanoPropertyKeys.AccountState.ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.AccountState.STAKE_BALANCE_INDEX_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.AccountHistory.ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.AccountHistory.TX_EVENTS_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.AccountHistory.REWARDS_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.Ledger.ADAPOT_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.Ledger.REWARDS_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED));
        assertEquals(false, resolved.globals().get(YanoPropertyKeys.SnapshotExport.ENABLED));
        assertEquals(true, resolved.globals().get(YanoPropertyKeys.Utxo.ENABLED));
        assertFalse(YanoAssembly.effectiveDerivedLedgerStateEnabled(config, true));
    }

    @Test
    void bootstrapPartialStatePolicyLeavesNonBootstrapOptionsUnchanged() {
        YanoConfig config = YanoConfig.serverOnly(0);
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                YanoPropertyKeys.AccountState.ENABLED, true));

        RuntimeOptions resolved = YanoAssembly.applyBootstrapPartialStatePolicy(config, options);

        assertSame(options, resolved);
        assertTrue(YanoAssembly.effectiveDerivedLedgerStateEnabled(config, true));
    }

    @Test
    void preStartConfigurationIsAppliedBeforeTransactionBootstrap() {
        BootstrapDataProvider provider = (BootstrapDataProvider) Proxy.newProxyInstance(
                BootstrapDataProvider.class.getClassLoader(),
                new Class<?>[]{BootstrapDataProvider.class},
                (proxy, method, args) -> method.getReturnType() == List.class ? List.of() : null);
        AtomicBoolean called = new AtomicBoolean();

        Yano node = YanoAssembly.relay(YanoConfig.serverOnly(0))
                .adhocRollback(123L, -1)
                .bootstrapDataProvider(provider)
                .transactionBootstrap(TransactionBootstrapOptions.enabled(false, false, "scalus"),
                        (context, options) -> {
                            called.set(true);
                            var runtime = (RuntimeTransactionBootstrapContext) context;
                            assertSame(provider, field(runtime.runtime(), "bootstrapDataProvider"));
                            assertEquals(123L, field(runtime.runtime(), "adhocRollbackToSlot"));
                            assertEquals(-1, field(runtime.runtime(), "adhocRollbackToEpoch"));
                            return Optional.empty();
                        })
                .build();

        try {
            assertTrue(called.get());
        } finally {
            node.close();
        }
    }

    @Test
    void devnetRecipeRequiresDevnetProducerConfig() {
        assertThrows(IllegalStateException.class,
                () -> YanoAssembly.devnet(YanoConfig.serverOnly(0)).build());
    }

    @Test
    void timeTravelRecipeRequiresPastTimeTravelMode() {
        assertThrows(IllegalStateException.class,
                () -> YanoAssembly.devnetTimeTravel(devnetConfig("missing-time-travel")).build());
    }

    @Test
    void devnetRecipeInstallsLiveDevnetProducerPlan() {
        Yano node = YanoAssembly.devnet(devnetConfig("devnet")).build();

        try {
            ProducerStartupPlan plan = producerStartupPlan(node);
            assertEquals(ProducerMode.DEVNET, plan.mode());
            assertFalse(plan.deferredUntilGenesisShift());
            assertTrue(node.devnetControl().isEmpty());
            assertTrue(((DevnetRuntimeProvider) node).devnetRuntime().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void slotLeaderRecipeInstallsLiveSlotLeaderProducerPlan() {
        YanoConfig config = devnetConfig("slot-leader");
        config.setSlotLeaderMode(true);

        Yano node = YanoAssembly.slotLeader(config).build();

        try {
            ProducerStartupPlan plan = producerStartupPlan(node);
            assertEquals(ProducerMode.SLOT_LEADER, plan.mode());
            assertFalse(plan.deferredUntilGenesisShift());
        } finally {
            node.close();
        }
    }

    @Test
    void slotLeaderRecipeRequiresSlotLeaderConfig() {
        assertThrows(IllegalStateException.class,
                () -> YanoAssembly.slotLeader(devnetConfig("missing-slot-leader")).build());
    }

    @Test
    void fromConfigRoutesSlotLeaderBeforeDevnetRecipe() {
        YanoConfig config = devnetConfig("from-config-slot-leader");
        config.setSlotLeaderMode(true);

        Yano node = YanoAssembly.fromConfig(config).build();

        try {
            ProducerStartupPlan plan = producerStartupPlan(node);
            assertEquals(ProducerMode.SLOT_LEADER, plan.mode());
            assertFalse(plan.deferredUntilGenesisShift());
            assertTrue(node.devnetControl().isEmpty());
        } finally {
            node.close();
        }
    }

    @Test
    void fromConfigRoutesDevnetAndTimeTravelRecipes() {
        Yano devnetNode = YanoAssembly.fromConfig(devnetConfig("from-config-devnet")).build();
        try {
            ProducerStartupPlan plan = producerStartupPlan(devnetNode);
            assertEquals(ProducerMode.DEVNET, plan.mode());
            assertFalse(plan.deferredUntilGenesisShift());
            assertTrue(devnetNode.devnetControl().isEmpty());
            assertTrue(((DevnetRuntimeProvider) devnetNode).devnetRuntime().isPresent());
        } finally {
            devnetNode.close();
        }

        YanoConfig timeTravelConfig = devnetConfig("from-config-time-travel");
        timeTravelConfig.setPastTimeTravelMode(true);
        Yano timeTravelNode = YanoAssembly.fromConfig(timeTravelConfig).build();
        try {
            ProducerStartupPlan plan = producerStartupPlan(timeTravelNode);
            assertEquals(ProducerMode.DEVNET_TIME_TRAVEL, plan.mode());
            assertTrue(plan.deferredUntilGenesisShift());
            assertTrue(timeTravelNode.devnetControl().isEmpty());
            assertTrue(((DevnetRuntimeProvider) timeTravelNode).devnetRuntime().isPresent());
        } finally {
            timeTravelNode.close();
        }
    }

    @Test
    void fromConfigTreatsIsolatedPastTimeTravelSlotLeaderFlagAsNormalDevnet() {
        YanoConfig config = devnetConfig("isolated-ptt-slot-leader");
        config.setPastTimeTravelSlotLeaderMode(true);

        Yano node = YanoAssembly.fromConfig(config).build();

        try {
            ProducerStartupPlan plan = producerStartupPlan(node);
            assertEquals(ProducerMode.DEVNET, plan.mode());
            assertFalse(plan.deferredUntilGenesisShift());
        } finally {
            node.close();
        }
    }

    @Test
    void fromConfigRoutesNonDevnetProducerConfigToRelayRecipe() {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setEnableBlockProducer(false);

        Yano node = YanoAssembly.fromConfig(config).build();

        try {
            assertTrue(node.producerControl().isEmpty());
            assertTrue(node.devnetControl().isEmpty());
        } finally {
            node.close();
        }
    }

    @Test
    void devnetTimeTravelRecipeInstallsDeferredDevnetProducerPlan() {
        YanoConfig config = devnetConfig("devnet-time-travel");
        config.setPastTimeTravelMode(true);

        Yano node = YanoAssembly.devnetTimeTravel(config).build();

        try {
            ProducerStartupPlan plan = producerStartupPlan(node);
            assertEquals(ProducerMode.DEVNET_TIME_TRAVEL, plan.mode());
            assertTrue(plan.deferredUntilGenesisShift());
            assertTrue(node.devnetControl().isEmpty());
            assertTrue(((DevnetRuntimeProvider) node).devnetRuntime().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void devnetTimeTravelRecipeSupportsSlotLeaderTimeTravelPlan() {
        YanoConfig config = devnetConfig("slot-leader-time-travel");
        config.setPastTimeTravelMode(true);
        config.setPastTimeTravelSlotLeaderMode(true);

        Yano node = YanoAssembly.devnetTimeTravel(config).build();

        try {
            ProducerStartupPlan plan = producerStartupPlan(node);
            assertEquals(ProducerMode.SLOT_LEADER_TIME_TRAVEL, plan.mode());
            assertTrue(plan.deferredUntilGenesisShift());
            assertTrue(node.devnetControl().isEmpty());
            assertTrue(((DevnetRuntimeProvider) node).devnetRuntime().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void devnetRecipeRejectsSlotLeaderOrTimeTravelFlags() {
        YanoConfig config = devnetConfig("reject-slot-leader");
        config.setSlotLeaderMode(true);

        assertThrows(IllegalStateException.class, () -> YanoAssembly.devnet(config).build());
    }

    @Test
    void devnetTimeTravelRecipeRejectsAmbiguousSlotLeaderFlag() {
        YanoConfig config = devnetConfig("reject-ambiguous-slot-leader");
        config.setPastTimeTravelMode(true);
        config.setSlotLeaderMode(true);

        assertThrows(IllegalStateException.class, () -> YanoAssembly.devnetTimeTravel(config).build());
    }

    @Test
    void runtimeNodeCloseDelegatesToAutoCloseable() {
        AtomicBoolean closed = new AtomicBoolean(false);
        TestRuntimeNode nodeApi = runtimeNode(
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "CloseableRuntimeNode";
                    }
                    return defaultValue(method);
                });

        runtimeYano(nodeApi).close();

        assertTrue(closed.get());
    }

    @Test
    void runtimeNodeDevnetControlIsAlwaysEmpty() {
        TestRuntimeNode nodeApi = runtimeNode(
                (proxy, method, args) -> {
                    if ("getConfig".equals(method.getName())) {
                        return YanoConfig.devnetDefault(0);
                    }
                    if ("toString".equals(method.getName())) {
                        return "DevnetConfigRuntimeNode";
                    }
                    return defaultValue(method);
                });

        assertTrue(runtimeYano(nodeApi, YanoAssembly.Role.RELAY).devnetControl().isEmpty());
        assertTrue(runtimeYano(nodeApi, YanoAssembly.Role.DEVNET).devnetControl().isEmpty());
        assertTrue(runtimeYano(nodeApi, YanoAssembly.Role.SLOT_LEADER).devnetControl().isEmpty());
        assertTrue(runtimeYano(nodeApi, YanoAssembly.Role.DEVNET_TIME_TRAVEL).devnetControl().isEmpty());
    }

    @Test
    void runtimeLifecycleStopIsRestartableButCloseIsTerminal() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        TestRuntimeNode nodeApi = runtimeNode(
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "start" -> {
                            starts.incrementAndGet();
                            return null;
                        }
                        case "stop" -> {
                            stops.incrementAndGet();
                            return null;
                        }
                        case "close" -> {
                            closes.incrementAndGet();
                            return null;
                        }
                        case "getConfig" -> {
                            return YanoConfig.serverOnly(0);
                        }
                        case "toString" -> {
                            return "RestartableRuntimeNode";
                        }
                        default -> {
                            return defaultValue(method);
                        }
                    }
                });

        RuntimeYano node = runtimeYano(nodeApi);
        node.start();
        node.stop();
        node.start();
        node.close();

        assertEquals(2, starts.get());
        assertEquals(2, stops.get());
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, node::start);
    }

    @Test
    void runtimeKernelHealthReportsRuntimeDegraded() {
        TestRuntimeNode nodeApi = statusNode(NodeStatus.builder()
                .running(true)
                .runtimeDegraded(true)
                .runtimeDegradedReason("restart required")
                .build());

        var health = runtimeYano(nodeApi).kernel().orElseThrow().health();

        assertEquals(SubsystemHealth.Status.DEGRADED, health.getFirst().status());
        assertEquals("restart required", health.getFirst().message());
    }

    @Test
    void runtimeKernelHealthReportsTerminalPeerRecoveryDown() {
        TestRuntimeNode nodeApi = statusNode(NodeStatus.builder()
                .running(true)
                .peerRecoveryTerminal(true)
                .peerTerminalFailureMessage("peer recovery exhausted")
                .build());

        var health = runtimeYano(nodeApi).kernel().orElseThrow().health();

        assertEquals(SubsystemHealth.Status.DOWN, health.getFirst().status());
        assertEquals("peer recovery exhausted", health.getFirst().message());
    }

    private static TestRuntimeNode statusNode(NodeStatus status) {
        return runtimeNode(
                (proxy, method, args) -> {
                    if ("getStatus".equals(method.getName())) {
                        return status;
                    }
                    if ("getConfig".equals(method.getName())) {
                        return YanoConfig.serverOnly(0);
                    }
                    if ("toString".equals(method.getName())) {
                        return "StatusRuntimeNode";
                    }
                    return defaultValue(method);
                });
    }

    private YanoConfig devnetConfig(String name) {
        YanoConfig config = YanoConfig.devnetDefault(0);
        config.setRocksDBPath(tempDir.resolve(name).toString());
        return config;
    }

    private static RuntimeYano runtimeYano(TestRuntimeNode node) {
        return runtimeYano(node, YanoAssembly.Role.RELAY);
    }

    private static RuntimeYano runtimeYano(TestRuntimeNode node, YanoAssembly.Role role) {
        return new RuntimeYano(
                node,
                node,
                node,
                node,
                node,
                node,
                new RuntimeMaintenanceGate(),
                node,
                node,
                role);
    }

    private static ProducerStartupPlan producerStartupPlan(Yano node) {
        return (ProducerStartupPlan) field(node.producerControl().orElseThrow(), "producerStartupPlanOverride");
    }

    private static TestRuntimeNode runtimeNode(InvocationHandler handler) {
        return (TestRuntimeNode) Proxy.newProxyInstance(
                TestRuntimeNode.class.getClassLoader(),
                new Class<?>[]{TestRuntimeNode.class},
                handler);
    }

    private static Object defaultValue(Method method) {
        if ("getConfig".equals(method.getName())) {
            return YanoConfig.serverOnly(0);
        }
        if ("getDefaultAccountStateStore".equals(method.getName())
                || method.getReturnType() == Optional.class) {
            return Optional.empty();
        }
        if (method.getReturnType() == boolean.class) {
            return false;
        }
        if (method.getReturnType() == long.class) {
            return 0L;
        }
        if (method.getReturnType() == int.class) {
            return 0;
        }
        return null;
    }

    private interface TestRuntimeNode extends NodeLifecycle, ChainQuery, LedgerQuery, TxGateway,
            TxEvaluationGateway, ProducerControl, DebugLedgerStateAccess, AutoCloseable {
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable failure)
            throws T {
        throw (T) failure;
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }
}
