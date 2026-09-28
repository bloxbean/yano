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

import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaEngineFactoryTest {

    @Test
    void isDiscoveredAndRefusedWithoutTheExperimentalFlag() {
        LedgerValidationEngines engines = LedgerValidationEngines.discover(getClass().getClassLoader());
        assertThat(engines.available()).contains("java");
        assertThatThrownBy(() -> engines.factory("java").create(context(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'java' is not available yet")
                .hasMessageContaining(JavaEngineFactory.EXPERIMENTAL_KEY);
        assertThatThrownBy(() -> engines.factory("java").create(context(Map.of(JavaEngineFactory.EXPERIMENTAL_KEY,
                "yes")))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsTheEngineWithTheFlag() {
        LedgerValidationEngine engine = new JavaEngineFactory().create(
                context(Map.of(JavaEngineFactory.EXPERIMENTAL_KEY, " TRUE ")));
        assertThat(engine.name()).isEqualTo("java");
        assertThat(engine).isInstanceOf(JavaLedgerValidationEngine.class);
    }

    @Test
    void refusesProtocolVersionsOutsideConwayTenAndEleven() {
        byte[] cbor = EngineTestSupport.build(MutationWorld.simpleSpec()).cbor();
        for (int major : new int[]{9, 12}) {
            var outcome = new JavaLedgerValidationEngine(new StubEvaluator()).validate(new TxValidationRequest(cbor,
                    MutationWorld.builder(EngineTestSupport.params(major)).build(), EngineTestSupport.env(major),
                    TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
            assertThat(EngineTestSupport.names(outcome)).as("PV %d", major).containsExactly("ENGINE.EraNotSupported");
        }
    }

    @Test
    void undecodableBytesAreADecodingFailure() {
        var outcome = EngineTestSupport.validate(new StubEvaluator(), new byte[]{(byte) 0x84, 0x01});
        assertThat(EngineTestSupport.names(outcome)).containsExactly("ENGINE.DecodingFailure");
    }

    private static EngineContext context(Map<String, String> config) {
        return new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return Optional.ofNullable(config.get(key));
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
                return new StubEvaluator();
            }

            @Override
            public int validationThreads() {
                return 1;
            }
        };
    }
}
