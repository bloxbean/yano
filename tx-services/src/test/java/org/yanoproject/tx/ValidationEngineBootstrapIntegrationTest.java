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
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.assembly.YanoAssembly;
import org.yanoproject.runtime.tx.TransactionBootstrapOptions;
import org.yanoproject.runtime.tx.TransactionServices;
import org.yanoproject.runtime.validation.ValidationEngineConfigurationException;
import org.yanoproject.scalusbridge.ScalusLedgerValidationEngine;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

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

    @Test
    void defaultConfigurationCreatesNoEngines(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(), services)) {
            assertThat(services.get().validator()).isNotNull();
            assertThat(services.get().validationEngines()).isNull();
        }
    }

    @Test
    void scalusWithAShadowEngineKeepsLegacyAdmission(@TempDir Path dir) {
        AtomicReference<TransactionServices> services = new AtomicReference<>();
        try (Yano node = build(dir, Map.of(YanoPropertyKeys.Validation.SHADOW_ENGINES, "capture",
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
            assertThat(context.validationThreads()).isEqualTo(3);
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
    void supplementaryRulesCannotCombineWithAnotherAdmissionEngine(@TempDir Path dir) {
        assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "capture"),
                new AtomicReference<>(), true))
                .isInstanceOf(ValidationEngineConfigurationException.class)
                .hasMessageContaining("supplementary-rules-enabled=true applies to the legacy Scalus validator only");
    }

    @Test
    void javaEngineStopsStartup(@TempDir Path dir) {
        assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "java"),
                new AtomicReference<>()))
                .isInstanceOf(ValidationEngineConfigurationException.class)
                .hasMessageContaining("'java' is experimental");
    }

    @Test
    void amaruWithoutTheModuleStopsStartup(@TempDir Path dir) {
        assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.ENGINE, "amaru"),
                new AtomicReference<>()))
                .isInstanceOf(ValidationEngineConfigurationException.class)
                .hasMessageContaining("amaru-validator module is not on the classpath");
        assertThatThrownBy(() -> build(dir, Map.of(YanoPropertyKeys.Validation.SHADOW_ENGINES, "amaru"),
                new AtomicReference<>()))
                .isInstanceOf(ValidationEngineConfigurationException.class)
                .hasMessageContaining("-PwithAmaru=true");
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
