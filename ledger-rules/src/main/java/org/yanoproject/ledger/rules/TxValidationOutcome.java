package org.yanoproject.ledger.rules;

import org.yanoproject.ledger.rules.effects.TxEffects;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link LedgerValidationEngine#validate(TxValidationRequest)} (ADR-056 §2).
 */
public sealed interface TxValidationOutcome permits TxValidationOutcome.Valid, TxValidationOutcome.Invalid {

    /**
     * The transaction is valid. Validation had no side effects; the caller applies {@code effects}
     * to its overlay (invariant 3).
     *
     * @param effects   the transaction's effects for its phase-2 verdict
     * @param validated provenance to keep and pass back as {@code previous}
     * @param reapplied true when static checks were skipped because {@code previous} was reusable
     */
    record Valid(TxEffects effects, ValidatedTx validated, boolean reapplied) implements TxValidationOutcome {
        public Valid {
            Objects.requireNonNull(effects, "effects");
            Objects.requireNonNull(validated, "validated");
        }
    }

    /** @param failures at least one failure, in the order the rules reported them */
    record Invalid(List<LedgerFailure> failures) implements TxValidationOutcome {
        public Invalid {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("An invalid outcome needs at least one failure");
            }
        }

        public static Invalid of(LedgerFailure failure) {
            return new Invalid(List.of(failure));
        }
    }

    default boolean isValid() {
        return this instanceof Valid;
    }

    /** Maps to the legacy {@link ValidationResult} used by REST, n2n and n2c rejection paths. */
    default ValidationResult toValidationResult() {
        return switch (this) {
            case Valid v -> ValidationResult.success();
            case Invalid i -> ValidationResult.failure(
                    i.failures().stream().map(LedgerFailure::toValidationError).toList());
        };
    }
}
