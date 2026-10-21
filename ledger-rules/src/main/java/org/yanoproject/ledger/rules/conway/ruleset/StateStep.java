package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.CheckLabel;

import java.util.List;
import java.util.Objects;

/**
 * A state-transition step: it advances the transition's state and always runs (dynamic), whatever the validation
 * mode. A step that cannot be taken throws, or reports an {@code ENGINE} failure by overriding
 * {@link #apply(Object)}.
 *
 * @param <S> the subject
 */
public abstract class StateStep<S> implements RuleUnit<S> {

    private final String id;
    private final String haskellRef;

    /**
     * @param id         {@code RULE.step}, e.g. {@code LEDGER.preCertificateStep}
     * @param haskellRef where Haskell takes the step
     */
    protected StateStep(String id, String haskellRef) {
        this.id = Objects.requireNonNull(id, "id");
        this.haskellRef = Objects.requireNonNull(haskellRef, "haskellRef");
    }

    /** Takes the step. */
    protected abstract void advance(S subject);

    @Override
    public List<LedgerFailure> apply(S subject) {
        advance(subject);
        return List.of();
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final UnitKind kind() {
        return UnitKind.STEP;
    }

    @Override
    public final CheckLabel label() {
        return CheckLabel.DYNAMIC;
    }

    @Override
    public final String haskellRef() {
        return haskellRef;
    }
}
