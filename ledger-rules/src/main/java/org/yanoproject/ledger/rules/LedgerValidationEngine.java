package org.yanoproject.ledger.rules;

/**
 * A selectable admission engine (ADR-056 §2, §7): {@code scalus}, {@code java-julc}, {@code java-scalus} or
 * {@code amaru}.
 *
 * <p>This is the new engine SPI. {@link TransactionValidator} stays unchanged as the legacy
 * adapter over canonical state until Phase 6 removes it.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Side-effect free: nothing is written anywhere; effects are returned (invariant 3).</li>
 *   <li>Fail closed: an unavailable read, a conversion error or an unexpected exception is an
 *       {@link TxValidationOutcome.Invalid} with an {@link LedgerRuleName#ENGINE} failure, never a
 *       thrown exception and never {@code Valid} (invariant 2).</li>
 *   <li>Deterministic effects: a {@code Valid} outcome's effects come from
 *       {@code TxEffectsDeriver} for the verdict, so they do not depend on the engine
 *       (invariant 4).</li>
 * </ul>
 */
public interface LedgerValidationEngine {

    /** @return the engine name used in configuration and metrics */
    String name();

    TxValidationOutcome validate(TxValidationRequest request);

    /**
     * Whether the engine can currently answer requests. An unhealthy engine keeps failing closed (every
     * request is {@link TxValidationOutcome.Invalid}); the node reports it through its health checks.
     *
     * @return true unless the engine has turned unhealthy (for example the Amaru engine after too many
     *         abandoned calls)
     */
    default boolean isHealthy() {
        return true;
    }
}
