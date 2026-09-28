package org.yanoproject.ledger.rules;

import java.util.Objects;

/**
 * One typed validation failure (ADR-056 §2).
 *
 * <p>Ledger failures are named after the Haskell predicate-failure constructor valid for the
 * active protocol version (for example {@code DELEG}/{@code StakeKeyNotRegisteredDELEG}). Engine
 * failures use rule {@link LedgerRuleName#ENGINE} and a Yano constructor name; they are always
 * phase 1 and always reject.</p>
 *
 * @param rule        the Haskell rule, or {@link LedgerRuleName#ENGINE}
 * @param constructor the constructor name
 * @param phase       the validation phase
 * @param detail      structured detail for operators and clients (may be empty)
 */
public record LedgerFailure(LedgerRuleName rule, String constructor, Phase phase, String detail) {

    /** Engine constructor: a {@code LedgerView} read was unavailable (invariant 2). */
    public static final String LEDGER_STATE_UNAVAILABLE = "LedgerStateUnavailable";
    /** Engine constructor: not a Conway body, or a ticked protocol version below 10 (invariant 7). */
    public static final String ERA_NOT_SUPPORTED = "EraNotSupported";
    /** Engine constructor: Yano policy rejecting {@code isValid=false} submissions (§6). */
    public static final String PHASE2_INVALID_TX_NOT_SUPPORTED = "Phase2InvalidTxNotSupported";

    public enum Phase {
        PHASE_1,
        PHASE_2
    }

    public LedgerFailure {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(constructor, "constructor");
        if (constructor.isBlank()) {
            throw new IllegalArgumentException("constructor must not be blank");
        }
        Objects.requireNonNull(phase, "phase");
        detail = detail == null ? "" : detail;
    }

    public static LedgerFailure ledgerStateUnavailable(String detail) {
        return new LedgerFailure(LedgerRuleName.ENGINE, LEDGER_STATE_UNAVAILABLE, Phase.PHASE_1, detail);
    }

    public static LedgerFailure eraNotSupported(String detail) {
        return new LedgerFailure(LedgerRuleName.ENGINE, ERA_NOT_SUPPORTED, Phase.PHASE_1, detail);
    }

    public static LedgerFailure phase2InvalidTxNotSupported(String detail) {
        return new LedgerFailure(LedgerRuleName.ENGINE, PHASE2_INVALID_TX_NOT_SUPPORTED, Phase.PHASE_1, detail);
    }

    /** @return {@code RULE.Constructor}, e.g. {@code DELEG.StakeKeyNotRegisteredDELEG} */
    public String qualifiedName() {
        return rule.name() + "." + constructor;
    }

    /**
     * Maps to the legacy {@link ValidationError} so REST, n2n and n2c rejection paths are unchanged.
     * The error's {@code rule} is the constructor name (as the Scalus path reports class names), and
     * the message keeps the rule, constructor and detail, so no information is lost.
     */
    public ValidationError toValidationError() {
        String message = detail.isEmpty() ? qualifiedName() : qualifiedName() + ": " + detail;
        ValidationError.Phase legacyPhase = phase == Phase.PHASE_1
                ? ValidationError.Phase.PHASE_1
                : ValidationError.Phase.PHASE_2;
        return new ValidationError(constructor, message, legacyPhase);
    }
}
