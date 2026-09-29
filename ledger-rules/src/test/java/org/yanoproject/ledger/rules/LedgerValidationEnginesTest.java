package org.yanoproject.ledger.rules;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-056 §7: engine discovery and the startup errors for unavailable engines. */
class LedgerValidationEnginesTest {

    /** Registered in {@code META-INF/services} of the test classpath. */
    public static final class FakeFactory implements LedgerValidationEngineFactory {
        @Override
        public String name() {
            return "Fake";
        }

        @Override
        public LedgerValidationEngine create(EngineContext context) {
            return new LedgerValidationEngine() {
                @Override
                public String name() {
                    return "fake";
                }

                @Override
                public TxValidationOutcome validate(TxValidationRequest request) {
                    return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable("fake"));
                }
            };
        }
    }

    @Test
    void discoversFactoriesThroughServiceLoaderByLowercaseName() {
        LedgerValidationEngines engines = LedgerValidationEngines.discover(getClass().getClassLoader());

        assertThat(engines.available()).contains("fake");
        assertThat(engines.factory(" FAKE ").create(context()).name()).isEqualTo("fake");
    }

    @Test
    void javaWithoutItsFactoryIsNamed() {
        LedgerValidationEngines engines = LedgerValidationEngines.of(List.of(new FakeFactory()));

        assertThatThrownBy(() -> engines.factory("java"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'java' is not on the classpath")
                .hasMessageContaining("JavaEngineFactory");
    }

    @Test
    void amaruWithoutTheModuleFailsClearly() {
        LedgerValidationEngines engines = LedgerValidationEngines.discover(getClass().getClassLoader());

        assertThat(engines.available()).doesNotContain("amaru");
        assertThatThrownBy(() -> engines.factory("amaru"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("amaru-validator module is not on the classpath")
                .hasMessageContaining("-PwithAmaru=true");
    }

    @Test
    void unknownEngineListsTheAvailableOnes() {
        LedgerValidationEngines engines = LedgerValidationEngines.of(List.of(new FakeFactory()));

        assertThatThrownBy(() -> engines.factory("nope"))
                .hasMessageContaining("Unknown validation engine 'nope'")
                .hasMessageContaining("fake");
    }

    @Test
    void duplicateNamesAreRejected() {
        LedgerValidationEngineFactory other = new LedgerValidationEngineFactory() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public LedgerValidationEngine create(EngineContext context) {
                throw new UnsupportedOperationException();
            }
        };
        assertThatThrownBy(() -> LedgerValidationEngines.of(List.of(new FakeFactory(), other)))
                .hasMessageContaining("Two validation engines are named 'fake'");
    }

    @Test
    void shadowEngineListsParse() {
        assertThat(LedgerValidationEngines.parseShadowEngines(null, "scalus")).isEmpty();
        assertThat(LedgerValidationEngines.parseShadowEngines("", "scalus")).isEmpty();
        assertThat(LedgerValidationEngines.parseShadowEngines("[amaru, Java, amaru]", "scalus"))
                .containsExactly("amaru", "java");
        assertThat(LedgerValidationEngines.parseShadowEngines("'amaru'", "scalus")).containsExactly("amaru");
        assertThatThrownBy(() -> LedgerValidationEngines.parseShadowEngines("scalus,amaru", "Scalus"))
                .hasMessageContaining("must differ from yano.validation.engine");
    }

    @Test
    void engineHealthDefaultsToHealthy() {
        assertThat(new FakeFactory().create(context()).isHealthy()).isTrue();
    }

    private static EngineContext context() {
        return new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return Optional.empty();
            }

            @Override
            public Supplier<NetworkParameters> network() {
                return () -> {
                    throw new IllegalStateException("not needed");
                };
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
                return () -> 0;
            }

            @Override
            public ScriptPhaseEvaluator scriptPhaseEvaluator() {
                return null;
            }

            @Override
            public int validationThreads() {
                return 1;
            }
        };
    }
}
