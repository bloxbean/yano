package org.yanoproject.ledger.rules.fixtures.conformance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test as a negative test for one Conway predicate-failure constructor (ADR-056 §8, coverage matrix).
 *
 * <p>The value is {@code RULE.Constructor} as in {@link ConwayConstructorCatalogue}, for example
 * {@code @Covers("UTXO.FeeTooSmallUTxO")}. The annotation is repeatable. {@code CoverageMatrixTest} in
 * {@code ledger-conformance} scans test classes for it and fails on a name the catalogue does not know.</p>
 *
 * <p>{@link #pv()} names the protocol versions the test validates at; the per-protocol-version coverage (ADR-056
 * Phase 5c: a constructor must be covered at every version where it exists) counts the test only at those versions. The
 * mutation matrix's per-version coverage comes from its world matrix ({@code Mutations.worldCases}), not from this
 * attribute.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(Covers.List.class)
public @interface Covers {

    /** @return {@code RULE.Constructor}, e.g. {@code GOV.ProposalDepositIncorrect} */
    String value();

    /** @return the protocol versions the test validates at; empty when it does not say (not counted per version) */
    int[] pv() default {};

    /** Container for repeated {@link Covers}. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List {
        Covers[] value();
    }
}
