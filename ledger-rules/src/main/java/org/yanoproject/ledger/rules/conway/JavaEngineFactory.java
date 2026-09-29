package org.yanoproject.ledger.rules.conway;

import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;

import java.util.Locale;

/**
 * {@code yano.validation.engine=java} (ADR-056 §7): the {@link JavaLedgerValidationEngine}.
 *
 * <p>Every Conway rule family is implemented and the ADR-056 Phase 5 gate passed (the Amaru scenarios, the complete
 * coverage matrix, the mutation matrix). The engine is still not the default and has not yet run behind the runtime
 * overlays (Phase 6), shadow sync or the native-image parity gate (Phase 7); Phase 8 makes it selectable without the
 * flag. Until then the factory creates it, as admission or shadow engine, only when
 * {@value #EXPERIMENTAL_KEY}{@code =true} is set explicitly (tests and the conformance harness set it); otherwise the
 * node stops at startup with this message.</p>
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
            throw new IllegalStateException("Validation engine 'java' is experimental: the Java Conway rules are "
                    + "complete (ADR-056 Phases 3-5) but have not yet run behind the runtime overlays, shadow sync and "
                    + "the native-image gate (Phases 6-7). Set " + EXPERIMENTAL_KEY + "=true to use it, or keep "
                    + "yano.validation.engine=scalus (the default), or amaru in a build with -PwithAmaru=true.");
        }
        return new JavaLedgerValidationEngine(context.scriptPhaseEvaluator());
    }
}
