package org.yanoproject.ledger.rules.shadow;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;

import java.util.Objects;
import java.util.Optional;

/**
 * What two engines are compared on (ADR-056 §7): valid or invalid, and for an invalid outcome the first
 * failure's rule and constructor. Details and later failures are not compared: engines word details
 * differently, and Amaru stops at the first failure where Haskell accumulates.
 *
 * @param valid        whether the outcome is valid
 * @param firstFailure the first failure of an invalid outcome, otherwise {@code null}
 */
public record Verdict(boolean valid, LedgerFailure firstFailure) {

    public Verdict {
        if (valid == (firstFailure != null)) {
            throw new IllegalArgumentException("a valid verdict has no failure, an invalid one has one");
        }
    }

    public static Verdict of(TxValidationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome) {
            case TxValidationOutcome.Valid v -> new Verdict(true, null);
            case TxValidationOutcome.Invalid i -> new Verdict(false, i.failures().getFirst());
        };
    }

    /** @return {@code VALID} or the first failure's {@code RULE.Constructor} */
    public String label() {
        return valid ? "VALID" : firstFailure.qualifiedName();
    }

    /** @return the first failure's rule, empty for a valid verdict */
    public Optional<LedgerRuleName> rule() {
        return valid ? Optional.empty() : Optional.of(firstFailure.rule());
    }

    /**
     * Compares two verdicts.
     *
     * @return empty when they agree, otherwise the disagreement
     */
    public static Optional<Disagreement> compare(Verdict admission, Verdict shadow) {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(shadow, "shadow");
        if (admission.valid != shadow.valid) {
            return Optional.of(new Disagreement(Disagreement.Kind.VERDICT, admission, shadow));
        }
        if (admission.valid) {
            return Optional.empty();
        }
        if (LegacyVerdicts.isWildcard(admission.firstFailure) || LegacyVerdicts.isWildcard(shadow.firstFailure)) {
            return Optional.empty(); // a legacy failure without a Haskell name: only the verdict is compared
        }
        boolean same = admission.firstFailure.rule() == shadow.firstFailure.rule()
                && admission.firstFailure.constructor().equals(shadow.firstFailure.constructor());
        return same ? Optional.empty()
                : Optional.of(new Disagreement(Disagreement.Kind.FAILURE, admission, shadow));
    }

    /**
     * Two engines disagree.
     *
     * @param kind      {@link Kind#VERDICT} when one accepts and the other rejects, {@link Kind#FAILURE} when
     *                  both reject with a different first rule or constructor
     * @param admission the admission engine's verdict
     * @param shadow    the shadow engine's verdict
     */
    public record Disagreement(Kind kind, Verdict admission, Verdict shadow) {

        public enum Kind {
            VERDICT,
            FAILURE
        }

        public Disagreement {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(shadow, "shadow");
        }

        /**
         * @return the metric's {@code rule} label: the first failing rule of the admission verdict, or of the
         *         shadow verdict when admission accepted
         */
        public String ruleLabel() {
            return admission.rule().or(shadow::rule).map(Enum::name).orElse("NONE");
        }

        @Override
        public String toString() {
            return kind + ": admission " + admission.label() + ", shadow " + shadow.label();
        }
    }
}
