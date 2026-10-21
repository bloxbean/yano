package org.yanoproject.ledger.rules.phase2;

import org.yanoproject.ledger.rules.LedgerFailure;

import java.util.List;
import java.util.Objects;

/** Result of {@link ScriptPhaseEvaluator#evaluate}. */
public sealed interface ScriptPhaseResult
        permits ScriptPhaseResult.Passed, ScriptPhaseResult.Failed, ScriptPhaseResult.Rejected {

    /** Every script ran and succeeded within its budget. */
    record Passed(List<ScriptOutcome> scripts) implements ScriptPhaseResult {
        public Passed {
            scripts = List.copyOf(Objects.requireNonNull(scripts, "scripts"));
        }
    }

    /**
     * At least one script failed or exceeded its budget: the transaction is phase-2 invalid (valid
     * with collateral only when it claims {@code is_valid = false}).
     */
    record Failed(List<ScriptOutcome> scripts) implements ScriptPhaseResult {
        public Failed {
            scripts = List.copyOf(Objects.requireNonNull(scripts, "scripts"));
        }
    }

    /**
     * No script ran: preparing the script context found a failure that Haskell reports in phase one
     * ({@code MalformedScriptWitnesses}, {@code MalformedReferenceScripts}, {@code CollectErrors}).
     *
     * @param failures at least one failure, each phase 1
     */
    record Rejected(List<LedgerFailure> failures) implements ScriptPhaseResult {
        public Rejected {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("A rejection needs at least one failure");
            }
        }
    }
}
