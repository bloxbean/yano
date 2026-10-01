package org.yanoproject.tx.shadowsync;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.EpochProtocolParamsSupplier;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.scripteval.phase2.JulcScriptPhaseEvaluator;
import org.yanoproject.runtime.validation.ValidationEngineSettings;
import org.yanoproject.runtime.validation.ValidationEngines;
import org.yanoproject.runtime.validation.shadowsync.SyncBlock;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.EngineResult;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.Kind;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7c: shadow sync with both phase-2 evaluators side by side ({@code shadow-sync-engines:
 * java-julc,java-scalus}). Every transaction gets a verdict from each engine, under its own engine id.
 */
class ShadowSyncBothEvaluatorsTest {

    @Test
    void createsJavaJulcAndJavaScalusForShadowSyncWithoutTouchingAdmission() {
        Map<String, Object> globals = Map.of(
                YanoPropertyKeys.Validation.ENGINE, "scalus",
                YanoPropertyKeys.Validation.SHADOW_SYNC, "true",
                YanoPropertyKeys.Validation.SHADOW_SYNC_ENGINES, "java-julc, java-scalus");
        ValidationEngineSettings settings = ValidationEngineSettings.fromGlobals(globals);
        try (ValidationEngines engines = ValidationEngines.create(settings,
                LedgerValidationEngines.discover(getClass().getClassLoader()), context(),
                (slot, view) -> MutationWorld.env())) {
            assertThat(engines.affectsAdmission()).isFalse();
            assertThat(engines.shadowSyncEngines()).extracting(LedgerValidationEngine::name)
                    .containsExactly("java-julc", "java-scalus");
            assertThat(engines.status().shadowSyncHealth()).containsOnlyKeys("java-julc", "java-scalus");
            // Each runs its own evaluator: a failing script is reported in that evaluator's words.
            TxValidationRequest failing = new TxValidationRequest(failingScriptTx(true), MutationWorld.view(),
                    MutationWorld.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null);
            String julc = detail(engines.shadowSyncEngines().get(0), failing);
            String scalus = detail(engines.shadowSyncEngines().get(1), failing);
            assertThat(julc).isEqualTo(detail(new JavaLedgerValidationEngine(new JulcScriptPhaseEvaluator()), failing));
            assertThat(scalus).isEqualTo(detail(new JavaLedgerValidationEngine(LedgerValidationEngines.JAVA_SCALUS,
                    new ScalusScriptPhaseEvaluator()), failing)).isNotEqualTo(julc);
        }
    }

    /** A passing Plutus spend and a failing one listed in {@code invalid_transactions}: both engines agree. */
    @Test
    void everyTransactionGetsBothVerdicts() {
        byte[] passing = ConwayTxBuilder.build(MutationWorld.scriptSpec(), MutationWorld.view()).cbor();
        SyncBlock block = new SyncBlock(List.of(passing), List.of(TxIdentity.txIdHex(passing)), Set.of());
        List<LedgerValidationEngine> engines = List.of(
                new JavaLedgerValidationEngine(new JulcScriptPhaseEvaluator()),
                new JavaLedgerValidationEngine(LedgerValidationEngines.JAVA_SCALUS, new ScalusScriptPhaseEvaluator()));

        List<EngineResult> results = new SyncBlockValidator().validate(block, MutationWorld.view(),
                MutationWorld.env(), engines, null).engines();
        assertThat(results).extracting(EngineResult::engine).containsExactly("java-julc", "java-scalus");
        results.forEach(r -> assertThat(r.txs().getFirst().kind()).as(r.engine()).isEqualTo(Kind.AGREED));

        byte[] failing = failingScriptTx(false);
        SyncBlock invalid = new SyncBlock(List.of(failing), List.of(TxIdentity.txIdHex(failing)), Set.of(0));
        results = new SyncBlockValidator().validate(invalid, MutationWorld.view(), MutationWorld.env(), engines, null)
                .engines();
        results.forEach(r -> {
            assertThat(r.txs().getFirst().kind()).as(r.engine()).isEqualTo(Kind.AGREED);
            assertThat(r.txs().getFirst().actual()).isEqualTo("PHASE2_INVALID");
        });
    }

    private static byte[] failingScriptTx(boolean isValid) {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.remove(MutationWorld.SCRIPT_INPUT);
        spec.inputs.add(MutationWorld.FAIL_SCRIPT_INPUT);
        spec.plutusScripts.clear();
        spec.plutusScripts.add(MutationWorld.ALWAYS_FAILS);
        spec.isValid = isValid;
        return ConwayTxBuilder.build(spec, MutationWorld.view()).cbor();
    }

    private static String detail(LedgerValidationEngine engine, TxValidationRequest request) {
        return ((TxValidationOutcome.Invalid) engine.validate(request)).failures().getFirst().detail();
    }

    /** The node's context as {@code ValidationEngineBootstrap} builds it: both phase-2 evaluators. */
    private static EngineContext context() {
        ScriptPhaseEvaluator scalus = new ScalusScriptPhaseEvaluator();
        ScriptPhaseEvaluator julc = new JulcScriptPhaseEvaluator();
        return new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return Optional.empty();
            }

            @Override
            public Supplier<NetworkParameters> network() {
                return () -> {
                    throw new IllegalStateException("not used");
                };
            }

            @Override
            public EpochProtocolParamsSupplier protocolParams() {
                return slot -> {
                    throw new IllegalStateException("not used");
                };
            }

            @Override
            public SlotConfigSupplier slotConfig() {
                return () -> MutationWorld.env().slotConfig();
            }

            @Override
            public LongSupplier currentSlot() {
                return () -> -1;
            }

            @Override
            public ScriptPhaseEvaluator scriptPhaseEvaluator() {
                return scalus;
            }

            @Override
            public ScriptPhaseEvaluator julcScriptPhaseEvaluator() {
                return julc;
            }

            @Override
            public int validationThreads() {
                return 1;
            }
        };
    }
}
