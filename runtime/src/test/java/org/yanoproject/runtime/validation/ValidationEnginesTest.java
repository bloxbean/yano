package org.yanoproject.runtime.validation;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.EpochProtocolParamsSupplier;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-056 Phase 7a: the context the shadow-sync engines are created with. */
class ValidationEnginesTest {

    private static final String POOL_SIZE = YanoPropertyKeys.Validation.AMARU_POOL_SIZE;

    @Test
    void shadowSyncResolvesAmaruPoolSizeZeroToMaxInFlight() {
        assertThat(shadowSyncContext(Optional.empty()).intConfig(POOL_SIZE, 0)).isEqualTo(5);
        assertThat(shadowSyncContext(Optional.of("0")).intConfig(POOL_SIZE, 0)).isEqualTo(5);
        assertThat(shadowSyncContext(Optional.of("3")).intConfig(POOL_SIZE, 0)).as("explicit").isEqualTo(3);
    }

    private static EngineContext shadowSyncContext(Optional<String> poolSize) {
        EngineContext node = new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return POOL_SIZE.equals(key) ? poolSize : Optional.empty();
            }

            @Override
            public Supplier<NetworkParameters> network() {
                return null;
            }

            @Override
            public EpochProtocolParamsSupplier protocolParams() {
                return null;
            }

            @Override
            public SlotConfigSupplier slotConfig() {
                return null;
            }

            @Override
            public LongSupplier currentSlot() {
                return null;
            }

            @Override
            public ScriptPhaseEvaluator scriptPhaseEvaluator() {
                return null;
            }

            @Override
            public int validationThreads() {
                return 4;
            }
        };
        AtomicReference<EngineContext> seen = new AtomicReference<>();
        LedgerValidationEngineFactory factory = new LedgerValidationEngineFactory() {
            @Override
            public String name() {
                return LedgerValidationEngines.AMARU;
            }

            @Override
            public LedgerValidationEngine create(EngineContext context) {
                seen.set(context);
                return new LedgerValidationEngine() {
                    @Override
                    public String name() {
                        return LedgerValidationEngines.AMARU;
                    }

                    @Override
                    public TxValidationOutcome validate(TxValidationRequest request) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
        ValidationEngineSettings settings = ValidationEngineSettings.fromGlobals(Map.of(
                YanoPropertyKeys.Validation.SHADOW_SYNC, "true",
                YanoPropertyKeys.Validation.SHADOW_SYNC_ENGINES, LedgerValidationEngines.AMARU,
                YanoPropertyKeys.Validation.SHADOW_SYNC_MAX_IN_FLIGHT, "5"));
        try (ValidationEngines ignored = ValidationEngines.create(settings, LedgerValidationEngines.of(List.of(factory)),
                node, (slot, view) -> null)) {
            return seen.get();
        }
    }
}
