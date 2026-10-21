package org.yanoproject.tx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.internal.RuntimeNode;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;
import org.yanoproject.tx.gate.ShadowSyncOrderingGuard;
import org.yanoproject.runtime.assembly.YanoAssembly;
import org.yanoproject.runtime.tx.TransactionBootstrapOptions;
import org.yanoproject.runtime.tx.TransactionServices;
import org.yanoproject.runtime.validation.ValidationEngineConfigurationException;
import org.yanoproject.scalusbridge.ScalusLedgerValidationEngine;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056 §7 / ADR-057 §2 through the real bootstrapper: engine selection, shadow registration, the engine
 * context, and the startup failures for engines that are not available.
 */
class ValidationEngineBootstrapIntegrationTest {

    /** A shadow engine registered for this test module only; it captures the context it is created with. */
    public static final class CapturingFactory implements LedgerValidationEngineFactory {
        static final AtomicReference<EngineContext> CONTEXT = new AtomicReference<>();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public LedgerValidationEngine create(EngineContext context) {
            CONTEXT.set(context);
            return new LedgerValidationEngine() {
                @Override
                public String name() {
                    return "capture";
                }

                @Override
                public TxValidationOutcome validate(TxValidationRequest request) {
                    return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable("capture"));
                }
            };
        }
    }

    /** ADR-056 Phase 8: with no engine configured, admission goes through {@code java-julc}. */
    @Test
    void defaultConfigurationAdmitsThroughJavaJulc(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(), services)) {
            var engines = services.get().validationEngines();
            assertThat(engines.admissionEngine().name()).isEqualTo("java-julc");
            assertThat(engines.affectsAdmission()).isTrue();
            assertThat(engines.shadowEngines()).isEmpty();
            assertThat(node.validationEngines()).containsSame(engines);
        }
    }

    @Test
    void javaScalusIsSelectable(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "java-scalus"), services)) {
            assertThat(services.get().validationEngines().admissionEngine().name()).isEqualTo("java-scalus");
        }
    }

    /** {@code engine: scalus} restores the legacy validator: no engines are created. */
    @Test
    void scalusAloneCreatesNoEngines(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "scalus"), services)) {
            assertThat(services.get().validator()).isNotNull();
            assertThat(services.get().validationEngines()).isNull();
        }
    }

    @Test
    void scalusWithAShadowEngineKeepsLegacyAdmission(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "scalus",
                YanoPropertyKeys.Validation.SHADOW_ENGINES, "capture",
                YanoPropertyKeys.Validation.AMARU_TIMEOUT_MS, "750"), services)) {
            var engines = services.get().validationEngines();
            assertThat(engines).isNotNull();
            assertThat(engines.legacyAdmission()).as("shadows never change admission").isTrue();
            assertThat(engines.admissionEngine()).isNull();
            assertThat(engines.shadowEngines()).extracting(LedgerValidationEngine::name).containsExactly("capture");
            assertThat(engines.shadowRunner()).isNotNull();
            assertThat(services.get().validator()).as("the legacy validator stays for block selection").isNotNull();

            EngineContext context = CapturingFactory.CONTEXT.get();
            assertThat(context.config(YanoPropertyKeys.Validation.AMARU_TIMEOUT_MS)).contains("750");
            assertThat(context.scriptPhaseEvaluator()).isInstanceOf(ScalusScriptPhaseEvaluator.class);
            assertThat(context.validationThreads()).isEqualTo(4);
            NetworkParameters network = context.network().get();
            assertThat(network.networkMagic()).isEqualTo(42);
            assertThat(network.firstNonByronSlot()).isZero();
            assertThat(network.epochLength()).isPositive();
            assertThat(network.slotLengthMs()).isPositive();
            assertThat(network.systemStartMs()).isPositive();
        }
    }

    @Test
    void aNonScalusAdmissionEngineUsesTheEngineApi(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "capture",
                YanoPropertyKeys.Validation.SHADOW_ENGINES, "scalus"), services)) {
            var engines = services.get().validationEngines();
            assertThat(engines.admissionEngine().name()).isEqualTo("capture");
            assertThat(engines.shadowEngines()).singleElement().isInstanceOf(ScalusLedgerValidationEngine.class);
        }
    }

    @Test
    void shadowSyncAloneCreatesItsOwnJavaEngineAndLeavesAdmissionLegacy(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "scalus",
                YanoPropertyKeys.Validation.SHADOW_SYNC, "true",
                YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_IN_FLIGHT, "3",
                YanoPropertyKeys.AccountState.ENABLED, true), services)) {
            var engines = services.get().validationEngines();
            assertThat(engines).isNotNull();
            assertThat(engines.affectsAdmission()).isFalse();
            assertThat(engines.admissionEngine()).isNull();
            assertThat(engines.shadowEngines()).isEmpty();
            // java-julc is the default shadow-sync engine.
            assertThat(engines.shadowSyncEngines()).extracting(LedgerValidationEngine::name)
                    .containsExactly("java-julc");
            assertThat(engines.settings().shadowSyncSettings().maxInFlight()).isEqualTo(3);
            assertThat(engines.shadowSync()).as("started by the runtime").isNotNull();
            assertThat(node.validationEngines()).containsSame(engines);
            assertThat(engines.status().shadowSyncHealth()).containsEntry("java-julc", true);

            // Installing the same engines again neither starts a second validator nor stops the running one.
            var running = engines.shadowSync();
            ((RuntimeNode) node.chain()).setValidationEngines(engines);
            assertThat(engines.shadowSync()).isSameAs(running);
            assertThat(running.status().inFlight()).isZero();

            // Ordering guard: the capture listener runs first, and nothing else below the UTxO apply (100) runs.
            PropagatingEventBus bus = (PropagatingEventBus) node.kernel().orElseThrow().context().eventBus();
            ShadowSyncOrderingGuard.assertCaptureRunsFirst(bus.subscribers(BlockAppliedEvent.class));
        }
    }

    @Test
    void shadowSyncIsNotStartedWithoutAccountStateAndTheNodeStillStarts(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "scalus",
                YanoPropertyKeys.Validation.SHADOW_SYNC, "true"), services)) {
            var engines = services.get().validationEngines();
            assertThat(engines.shadowSyncEngines()).isNotEmpty();
            assertThat(engines.shadowSync()).as("refused: the pre-block view needs account state").isNull();
            PropagatingEventBus bus = (PropagatingEventBus) node.kernel().orElseThrow().context().eventBus();
            assertThat(bus.subscribers(BlockAppliedEvent.class))
                    .noneMatch(sub -> sub.priority() == ShadowSyncValidator.SUBSCRIPTION_PRIORITY);
        }
    }

    @Test
    void supplementaryRulesCannotCombineWithAnotherAdmissionEngine(@TempDir Path dir) {
        assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "capture"),
                new AtomicReference<>(), true))
                .isInstanceOf(ValidationEngineConfigurationException.class)
                .hasMessageContaining("supplementary-rules-enabled=true applies to the legacy Scalus validator only");
    }

    /** The engine id {@code java} is gone (ADR-056 Phase 7c): startup names the two Java engine ids. */
    @Test
    void thePlainJavaEngineIdStopsStartupNamingItsReplacements(@TempDir Path dir) {
        assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "java"),
                new AtomicReference<>()))
                .isInstanceOf(ValidationEngineConfigurationException.class)
                .hasMessageContaining("Unknown validation engine 'java'")
                .hasMessageContaining("java-julc").hasMessageContaining("java-scalus");
    }

    @Test
    void amaruWithoutTheModuleStopsStartup(@TempDir Path dir) {
        for (String amaru : new String[]{"amaru", "amaru-scalus"}) {
            assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, amaru),
                    new AtomicReference<>()))
                    .isInstanceOf(ValidationEngineConfigurationException.class)
                    .hasMessageContaining("amaru-validator module is not on the classpath");
            assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.SHADOW_ENGINES, amaru),
                    new AtomicReference<>()))
                    .isInstanceOf(ValidationEngineConfigurationException.class)
                    .hasMessageContaining("-PwithAmaru=true");
        }
    }

    /**
     * ADR-056 Phase 7c: a listed engine provider that cannot be loaded (what a native image without the provider's
     * reflection registration reports) stops startup; before, the node ran on with no transaction validation.
     */
    @Test
    void anUnloadableEngineProviderStopsStartup(@TempDir Path dir) throws Exception {
        Path services = dir.resolve("broken/META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(LedgerValidationEngineFactory.class.getName()),
                "org.yanoproject.tx.MissingEngineFactory\n");
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader broken = new URLClassLoader(new URL[]{dir.resolve("broken").toUri().toURL()},
                previous)) {
            thread.setContextClassLoader(broken);
            assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.SHADOW_ENGINES, "capture"),
                    new AtomicReference<>()))
                    .isInstanceOf(ValidationEngineConfigurationException.class)
                    .hasMessageContaining("Cannot load a validation engine")
                    .hasMessageContaining("org.yanoproject.tx.MissingEngineFactory");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static Yano build(Path dir, Map<String, Object> validation,
                              AtomicReference<TransactionServices> captured) {
        return build(dir, validation, captured, false);
    }

    private static Yano build(Path dir, Map<String, Object> validation,
                              AtomicReference<TransactionServices> captured, boolean supplementaryRules) {
        YanoConfig config = YanoConfig.serverOnly(0);
        config.setUseRocksDB(true);
        config.setRocksDBPath(dir.resolve("chainstate").toString());
        config.setProtocolMagic(42);
        config.setShelleyGenesisFile(testPath("app/config/network/devnet/shelley-genesis.json").toString());
        config.setProtocolParametersFile(testPath("app/config/network/devnet/protocol-param.json").toString());
        Map<String, Object> globals = new HashMap<>(Map.of(
                "yano.utxo.enabled", true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.metrics.sample.rocksdb.seconds", 0));
        globals.putAll(validation);
        return YanoAssembly.relay(config)
                .runtimeOptions(new RuntimeOptions(null, null, globals))
                .transactionBootstrap(TransactionBootstrapOptions.enabled(false, supplementaryRules, "aiken"),
                        (context, options) -> {
                            var services = DefaultTransactionServicesFactory.create(context, options);
                            services.ifPresent(captured::set);
                            return services;
                        })
                .build();
    }

    private static Path testPath(String relative) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !root.resolve(relative).toFile().exists()) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("cannot find " + relative);
        }
        return root.resolve(relative);
    }
}
