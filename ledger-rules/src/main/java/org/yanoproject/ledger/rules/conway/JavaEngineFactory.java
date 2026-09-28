package org.yanoproject.ledger.rules.conway;

import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;

import java.util.Locale;

/**
 * {@code yano.validation.engine=java} (ADR-056 §7): the {@link JavaLedgerValidationEngine}.
 *
 * <p>Until the Phase 5 gate (every Conway rule family implemented, the coverage matrix complete) the engine is
 * incomplete: it would admit transactions that {@code UTXOW}, {@code CERTS}, {@code GOV} or the {@code LEDGER}
 * pre-checks reject. The factory therefore refuses to create it, as admission or shadow engine, unless
 * {@value #EXPERIMENTAL_KEY}{@code =true} is set explicitly (tests and the conformance harness set it). The node
 * then stops at startup with this message instead of running an incomplete engine.</p>
 */
public final class JavaEngineFactory implements LedgerValidationEngineFactory {

    /** Opt-in for the incomplete engine; never set it on a node that admits transactions. */
    public static final String EXPERIMENTAL_KEY = "yano.validation.java-engine.experimental";

    @Override
    public String name() {
        return JavaLedgerValidationEngine.NAME;
    }

    @Override
    public LedgerValidationEngine create(EngineContext context) {
        boolean experimental = context.config(EXPERIMENTAL_KEY)
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .filter("true"::equals)
                .isPresent();
        if (!experimental) {
            throw new IllegalStateException("Validation engine 'java' is not available yet: the Java Conway rules "
                    + "are incomplete until ADR-056 Phases 3-5 are done (Phase 3a: UTXO and UTXOS only). Use "
                    + "yano.validation.engine=scalus (the default), or amaru in a build with -PwithAmaru=true. "
                    + "Tests may set " + EXPERIMENTAL_KEY + "=true.");
        }
        return new JavaLedgerValidationEngine(context.scriptPhaseEvaluator());
    }
}
