package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.CheckLabel;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;

import java.util.List;
import java.util.Objects;

/**
 * A check that reports one {@link ConwayPredicate} constructor at most once ({@code runTest}, {@code ?!},
 * {@code failOnJust}, {@code failOnNonEmpty}): its id, label and Haskell reference come from the constructor.
 *
 * @param <S> the subject
 */
public abstract class PredicateCheck<S> implements RuleUnit<S> {

    private final ConwayPredicate predicate;
    private final String id;
    private final String haskellRef;

    /** The check of {@code predicate}, with its Haskell reference. */
    protected PredicateCheck(ConwayPredicate predicate) {
        this(predicate, null, predicate.haskellRef());
    }

    /**
     * @param predicate  the constructor it reports
     * @param suffix     distinguishes the checks of a constructor that Haskell checks in several places (the id is
     *                   {@code RULE.Constructor#suffix}), or null
     * @param haskellRef where Haskell checks it
     */
    protected PredicateCheck(ConwayPredicate predicate, String suffix, String haskellRef) {
        this.predicate = Objects.requireNonNull(predicate, "predicate");
        this.id = predicate.qualifiedName() + (suffix != null ? "#" + suffix : "");
        this.haskellRef = Objects.requireNonNull(haskellRef, "haskellRef");
    }

    /**
     * The check.
     *
     * @return the failure's detail (a rendering of the constructor's fields) when the check fails, or null when it
     *         holds
     */
    protected abstract String detail(S subject);

    @Override
    public final List<LedgerFailure> apply(S subject) {
        String detail = detail(subject);
        return detail == null ? List.of() : List.of(predicate.failure(detail));
    }

    public final ConwayPredicate predicate() {
        return predicate;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final UnitKind kind() {
        return UnitKind.CHECK;
    }

    @Override
    public final CheckLabel label() {
        return predicate.label();
    }

    @Override
    public final String haskellRef() {
        return haskellRef;
    }

    @Override
    public final List<ConwayPredicate> reports() {
        return List.of(predicate);
    }
}
