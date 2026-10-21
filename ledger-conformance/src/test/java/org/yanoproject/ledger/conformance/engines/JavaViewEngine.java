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
import org.yanoproject.ledger.rules.conway.JavaJulcEngineFactory;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.conway.JavaScalusEngineFactory;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.scripteval.phase2.JulcScriptPhaseEvaluator;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The {@code java-julc} engine ({@link JavaLedgerValidationEngine}, ADR-056 Phases 3-5b) over the case's view: rule
 * {@code LEDGER}, origin {@code SYNC}, created through {@link JavaJulcEngineFactory}, and
 * with the julc {@code ScriptPhaseEvaluator} for Plutus; {@link #scalus()} is engine {@code java-scalus}, the same rules
 * with the Scalus evaluator ({@link JavaScalusEngineFactory}).
 *
 * <p>The evaluator gets the case's forecast horizon, as the node's does ({@code ValidationEngineBootstrap}): the
 * stability window and epoch geometry of the case's era history, basis the case's slot (the tip Amaru's fixtures
 * validate at). Conway builds script contexts with the unextended epoch info (Shelley/API/Mempool.hs:283-293,
 * Babbage/Rules/Ledgers.hs:126-133), so a validity bound past it is {@code UTXOS.CollectErrors [BadTranslation
 * TimeTranslationPastHorizon]} (scenario 00088).</p>
 */
public final class JavaViewEngine implements ConformanceEngine {

    private static final String DESCRIPTION = "JavaLedgerValidationEngine (ADR-056 Phases 3–5b: every Conway rule "
            + "family, LEDGER, GOV, CERTS, DELEG, POOL, GOVCERT, UTXOW, UTXO, UTXOS, protocol versions 9–11) over the "
            + "LedgerView, ";

    private final TxValidationRequest.Rule rule;
    private final String name;
    private final String description;
    private final Function<ForecastHorizon, JavaLedgerValidationEngine> engine;

    /** The engine under rule {@code LEDGER} (block selection and sync), as the gates run it. */
    public JavaViewEngine() {
        this(TxValidationRequest.Rule.LEDGER);
    }

    /** The engine under {@code rule} ({@code MEMPOOL} adds the mempool's own checks). */
    public JavaViewEngine(TxValidationRequest.Rule rule) {
        this(rule, "java-julc", DESCRIPTION + "julc phase 2 (Yano script contexts, julc CEK machine)",
                horizon -> create(new JulcScriptPhaseEvaluator(horizon)));
    }

    private JavaViewEngine(TxValidationRequest.Rule rule, String name, String description,
                           Function<ForecastHorizon, JavaLedgerValidationEngine> engine) {
        this.rule = rule;
        this.name = name;
        this.description = description;
        this.engine = engine;
    }

    /** {@code java-scalus} (ADR-056 Phase 7c): the same rules with the Scalus phase-2 evaluator. */
    public static JavaViewEngine scalus() {
        return new JavaViewEngine(TxValidationRequest.Rule.LEDGER, "java-scalus", DESCRIPTION + "Scalus phase 2",
                horizon -> createScalus(new ScalusScriptPhaseEvaluator(horizon)));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Observation validate(ConformanceCase testCase) {
        ForecastHorizon horizon = ForecastHorizon.of(testCase.network()::stabilityWindow,
                CaseTiming.geometry(testCase.network().eras()));
        JavaLedgerValidationEngine engine = this.engine.apply(horizon);
        if (!testCase.constants().isNone()) {
            engine = engine.withConstants(constants(testCase.constants()));
        }
        return Observation.of(engine.validate(new TxValidationRequest(testCase.txCbor(), testCase.view(),
                testCase.env(), rule, TxValidationRequest.Origin.SYNC, null)));
    }

    /** @return engine {@code java-julc} as the node would create it, with {@code julc} */
    public static JavaLedgerValidationEngine create(ScriptPhaseEvaluator julc) {
        return (JavaLedgerValidationEngine) new JavaJulcEngineFactory().create(context(null, julc));
    }

    /** @return engine {@code java-scalus} as the node would create it, with {@code scalus} */
    public static JavaLedgerValidationEngine createScalus(ScriptPhaseEvaluator scalus) {
        return (JavaLedgerValidationEngine) new JavaScalusEngineFactory().create(context(scalus, null));
    }

    static ConwayLedgerConstants constants(AmaruScenario.LedgerConstants c) {
        return ConwayLedgerConstants.HASKELL.with(c.maxRefScriptSizePerTx(), c.maxRefScriptSizePerBlock(),
                c.refScriptCostStride(), c.refScriptCostMultiplierNumerator(), c.refScriptCostMultiplierDenominator());
    }

    /** A minimal engine context: the evaluators; nothing else is read at creation. */
    private static EngineContext context(ScriptPhaseEvaluator scalus, ScriptPhaseEvaluator julc) {
        return new EngineContext() {
            @Override
            public Optional<String> config(String key) {
                return Optional.empty();
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
