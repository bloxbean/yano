package org.yanoproject.ledger.conformance.runner;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationOutcome;

import java.util.List;
import java.util.Objects;

/**
 * What an engine said about a case, with every failure named as a Haskell {@code RULE.Constructor} where the
 * engine's own failure could be mapped.
 *
 * @param valid    whether the engine accepted the transaction
 * @param failures the failures in the engine's order (empty when valid)
 */
public record Observation(boolean valid, List<Failure> failures) {

    /** Rule used for an engine failure that has no Haskell counterpart the adapter could identify. */
    public static final String UNMAPPED = "UNMAPPED";
    /** Rule used when the adapter or engine threw instead of answering. */
    public static final String CRASH = "CRASH";

    /**
     * One failure.
     *
     * @param rule        the Haskell rule, {@code ENGINE}, {@link #UNMAPPED} or {@link #CRASH}
     * @param constructor the Haskell constructor, or the engine's own name for unmapped failures
     * @param raw         the engine's own name and message (for the report)
     */
    public record Failure(String rule, String constructor, String raw) {
        public Failure {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(constructor, "constructor");
            raw = raw == null ? "" : raw;
        }

        public String qualifiedName() {
            return rule + "." + constructor;
        }

        public boolean mapped() {
            return !UNMAPPED.equals(rule) && !CRASH.equals(rule);
        }
    }

    public Observation {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (!valid && failures.isEmpty()) {
            throw new IllegalArgumentException("an invalid observation needs a failure");
        }
    }

    public static Observation accepted() {
        return new Observation(true, List.of());
    }

    public static Observation rejected(List<Failure> failures) {
        return new Observation(false, failures);
    }

    public static Observation crash(Throwable error) {
        return new Observation(false, List.of(new Failure(CRASH, error.getClass().getSimpleName(),
                String.valueOf(error.getMessage()))));
    }

    /** @return the observation for an engine-API outcome (failures are already Haskell-named) */
    public static Observation of(TxValidationOutcome outcome) {
        return switch (outcome) {
            case TxValidationOutcome.Valid v -> accepted();
            case TxValidationOutcome.Invalid i -> rejected(i.failures().stream()
                    .map(f -> new Failure(f.rule().name(), f.constructor(), f.detail()))
                    .toList());
        };
    }

    /** @return the first failure, or null when valid */
    public Failure first() {
        return failures.isEmpty() ? null : failures.getFirst();
    }

    /** @return {@code Valid}, or the first failure's qualified name */
    public String label() {
        return valid ? "Valid" : first().qualifiedName();
    }

    /** Shortens engine messages for the report. */
    public static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    static boolean isDecodingFailure(Failure failure) {
        return failure != null && "DecodingFailure".equals(failure.constructor());
    }

    /** @return true when the failure is Yano's refusal below protocol version 10 (invariant 6) */
    public static boolean isEraNotSupported(Failure failure) {
        return failure != null && "ENGINE".equals(failure.rule())
                && LedgerFailure.ERA_NOT_SUPPORTED.equals(failure.constructor());
    }
}
