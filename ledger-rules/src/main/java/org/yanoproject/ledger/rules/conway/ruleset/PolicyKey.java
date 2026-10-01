package org.yanoproject.ledger.rules.conway.ruleset;

import java.util.Objects;

/**
 * Names a {@link RulePolicy} of a rule set.
 *
 * @param id   {@code RULE.function}, e.g. {@code GOVCERT.computeDRepExpiry}
 * @param type the policy's interface
 * @param <T>  the policy's interface
 */
public record PolicyKey<T extends RulePolicy>(String id, Class<T> type) {

    public PolicyKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public String toString() {
        return id;
    }
}
