package org.yanoproject.ledger.rules.conway;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.EpochProtocolParamsSupplier;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaEngineFactoriesTest {

    @Test
    void isDiscoveredAndTheFormerJavaIdIsRefused() {
        LedgerValidationEngines engines = LedgerValidationEngines.discover(getClass().getClassLoader());
        assertThat(engines.available()).contains("java-julc", "java-scalus").doesNotContain("java");
        assertThatThrownBy(() -> engines.factory("java")).hasMessageContaining("Unknown validation engine 'java'")
                .hasMessageContaining("java-julc").hasMessageContaining("java-scalus");
    }

    @Test
    void createsTheEngineWithoutAnOptIn() {
        LedgerValidationEngine engine = new JavaJulcEngineFactory().create(context(null, new StubEvaluator()));
        assertThat(engine.name()).isEqualTo("java-julc");
        assertThat(engine).isInstanceOf(JavaLedgerValidationEngine.class);
    }

    @Test
    void refusesProtocolVersionsOutsideConwayNineToEleven() {
        byte[] cbor = EngineTestSupport.build(MutationWorld.simpleSpec()).cbor();
        for (int major : new int[]{9, 10, 11}) {
            var outcome = new JavaLedgerValidationEngine(new StubEvaluator()).validate(new TxValidationRequest(cbor,
                    MutationWorld.builder(EngineTestSupport.params(major)).build(), EngineTestSupport.env(major),
                    TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
            assertThat(EngineTestSupport.names(outcome)).as("PV %d", major).containsExactly("Valid");
        }
        for (int major : new int[]{8, 12}) {
            var outcome = new JavaLedgerValidationEngine(new StubEvaluator()).validate(new TxValidationRequest(cbor,
                    MutationWorld.builder(EngineTestSupport.params(major)).build(), EngineTestSupport.env(major),
                    TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
            assertThat(EngineTestSupport.names(outcome)).as("PV %d", major).containsExactly("ENGINE.EraNotSupported");
        }
    }

    /**
     * ADR-056 Phase 7c: engine {@code java-julc} runs Plutus on the julc evaluator and stops startup without it; engine
     * {@code java-scalus} runs the same rules on the node's Scalus evaluator.
     */
    @Test
    void javaJulcUsesTheJulcEvaluatorAndJavaScalusTheScalusOne() {
        StubEvaluator scalus = new StubEvaluator();
        StubEvaluator julc = new StubEvaluator();
        LedgerValidationEngines engines = LedgerValidationEngines.discover(getClass().getClassLoader());

        var javaJulc = (JavaLedgerValidationEngine) engines.factory("java-julc").create(context(scalus, julc));
        assertThat(javaJulc.name()).isEqualTo("java-julc");
        assertThat(javaJulc.evaluator()).isSameAs(julc);
        assertThatThrownBy(() -> engines.factory("java-julc").create(context(scalus, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("julc phase-2 evaluator");

        var javaScalus = (JavaLedgerValidationEngine) engines.factory("java-scalus").create(context(scalus, julc));
        assertThat(javaScalus.name()).isEqualTo("java-scalus");
        assertThat(javaScalus.evaluator()).isSameAs(scalus);
        assertThat(javaScalus.withConstants(ConwayLedgerConstants.HASKELL).name()).isEqualTo("java-scalus");
    }

    @Test
    void undecodableBytesAreADecodingFailure() {
        var outcome = EngineTestSupport.validate(new StubEvaluator(), new byte[]{(byte) 0x84, 0x01});
        assertThat(EngineTestSupport.names(outcome)).containsExactly("ENGINE.DecodingFailure");
    }

    private static EngineContext context(ScriptPhaseEvaluator scalus, ScriptPhaseEvaluator julc) {
        return new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return Optional.empty();
            }

            @Override
            public Supplier<NetworkParameters> network() {
                return () -> null;
            }

            @Override
            public EpochProtocolParamsSupplier protocolParams() {
                return slot -> null;
            }

            @Override
            public SlotConfigSupplier slotConfig() {
                return () -> null;
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
