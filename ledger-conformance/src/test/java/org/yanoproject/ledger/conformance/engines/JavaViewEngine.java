package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.EpochProtocolParamsSupplier;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.JavaEngineFactory;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The {@code java} engine ({@link JavaLedgerValidationEngine}, ADR-056 Phases 3-5) over the case's view: rule
 * {@code LEDGER}, origin {@code SYNC}, created through {@link JavaEngineFactory} with the experimental flag, and
 * with the Scalus {@code ScriptPhaseEvaluator} for Plutus.
 *
 * <p>The evaluator gets the case's forecast horizon, as the node's does ({@code ValidationEngineBootstrap}): the
 * stability window and epoch geometry of the case's era history, basis the case's slot (the tip Amaru's fixtures
 * validate at). Conway builds script contexts with the unextended epoch info (Shelley/API/Mempool.hs:283-293,
 * Babbage/Rules/Ledgers.hs:126-133), so a validity bound past it is {@code UTXOS.CollectErrors [BadTranslation
 * TimeTranslationPastHorizon]} (scenario 00088).</p>
 */
public final class JavaViewEngine implements ConformanceEngine {


    @Override
    public String name() {
        return "java-engine";
    }

    @Override
    public String description() {
        return "JavaLedgerValidationEngine (ADR-056 Phase 3a: UTXO, UTXOS) over the LedgerView, Scalus phase 2";
    }

    @Override
    public Observation validate(ConformanceCase testCase) {
        ForecastHorizon horizon = ForecastHorizon.of(testCase.network()::stabilityWindow,
                CaseTiming.geometry(testCase.network().eras()));
        JavaLedgerValidationEngine engine = create(new ScalusScriptPhaseEvaluator(horizon));
        if (!testCase.constants().isNone()) {
            engine = engine.withConstants(constants(testCase.constants()));
        }
        return Observation.of(engine.validate(new TxValidationRequest(testCase.txCbor(), testCase.view(),
                testCase.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null)));
    }

    /** @return the engine as the node would create it, with the experimental opt-in and {@code evaluator} */
    public static JavaLedgerValidationEngine create(ScriptPhaseEvaluator evaluator) {
        return (JavaLedgerValidationEngine) new JavaEngineFactory().create(
                context(Map.of(JavaEngineFactory.EXPERIMENTAL_KEY, "true"), evaluator));
    }

    static ConwayLedgerConstants constants(AmaruScenario.LedgerConstants c) {
        return ConwayLedgerConstants.HASKELL.with(c.maxRefScriptSizePerTx(), c.maxRefScriptSizePerBlock(),
                c.refScriptCostStride(), c.refScriptCostMultiplierNumerator(), c.refScriptCostMultiplierDenominator());
    }

    /** A minimal engine context: configuration and the evaluator; nothing else is read at creation. */
    public static EngineContext context(Map<String, String> config, ScriptPhaseEvaluator evaluator) {
        return new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return Optional.ofNullable(config.get(key));
            }

            @Override
            public Supplier<NetworkParameters> network() {
                return () -> {
                    throw new IllegalStateException("not used by the conformance harness");
                };
            }

            @Override
            public EpochProtocolParamsSupplier protocolParams() {
                return slot -> {
                    throw new IllegalStateException("not used by the conformance harness");
                };
            }

            @Override
            public SlotConfigSupplier slotConfig() {
                return () -> {
                    throw new IllegalStateException("not used by the conformance harness");
                };
            }

            @Override
            public LongSupplier currentSlot() {
                return () -> -1;
            }

            @Override
            public ScriptPhaseEvaluator scriptPhaseEvaluator() {
                return evaluator;
            }

            @Override
            public int validationThreads() {
                return 1;
            }
        };
    }
}
