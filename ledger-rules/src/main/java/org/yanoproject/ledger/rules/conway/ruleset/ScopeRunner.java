package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.CheckLabel;

import java.util.List;
import java.util.function.Consumer;

/**
 * The one loop that runs a scope's units (ADR-056 Phase 5c), shared by {@code RuleFrame.run} and the engine-neutral
 * {@code MempoolRule.apply}: in order, each unit is one predicate whose failures go to {@code predicate}; a
 * {@code static} unit is skipped on re-application ({@code lblStatic}, ADR-056 §6); a unit that
 * {@linkplain RuleUnit#haltsOnFailure() halts} and fails ends the scope.
 */
public final class ScopeRunner {

    private ScopeRunner() {
    }

    /**
     * @param units     the scope's units, in execution order
     * @param subject   what they read
     * @param reapply   whether a validated transaction is being re-applied
     * @param predicate records one unit's failures (an empty list when the unit held)
     * @return true when a halting unit failed (the later units did not run)
     */
    public static <S> boolean run(List<RuleUnit<S>> units, S subject, boolean reapply,
                                  Consumer<List<LedgerFailure>> predicate) {
        for (RuleUnit<S> unit : units) {
            if (reapply && unit.label() == CheckLabel.STATIC) {
                continue;
            }
            List<LedgerFailure> failures = unit.apply(subject);
            predicate.accept(failures);
            if (unit.haltsOnFailure() && !failures.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
