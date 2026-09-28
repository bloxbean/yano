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
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(Covers.List.class)
public @interface Covers {

    /** @return {@code RULE.Constructor}, e.g. {@code GOV.ProposalDepositIncorrect} */
    String value();

    /** Container for repeated {@link Covers}. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List {
        Covers[] value();
    }
}
