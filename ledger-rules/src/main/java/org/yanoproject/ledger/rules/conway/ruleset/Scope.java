package org.yanoproject.ledger.rules.conway.ruleset;

import java.util.Objects;

/**
 * An ordered list of units within one Haskell rule (or one branch of it, such as one certificate kind of
 * {@code DELEG}), run against one subject type. The rule families run their scopes in Haskell's order
 * ({@link ConwayScopes}); within a scope the rule set's order is Haskell's order.
 *
 * @param name        e.g. {@code UTXO}, {@code DELEG.RegCert}, {@code GOV.proposal}
 * @param subjectType what the scope's units read
 * @param <S>         the subject type
 */
public record Scope<S>(String name, Class<S> subjectType) {

    public Scope {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(subjectType, "subjectType");
    }

    @Override
    public String toString() {
        return name;
    }
}
