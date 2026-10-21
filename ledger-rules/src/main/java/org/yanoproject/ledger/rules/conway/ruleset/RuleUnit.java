package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.CheckLabel;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;

import java.util.List;

/**
 * The unit of change of the Java Conway engine (ADR-056 Phase 5c): one Haskell predicate check, or one state step,
 * individually addressable by a stable {@link #id()} and composed into a protocol version's {@link ConwayRuleSet}.
 *
 * <p>A unit is stateless (its fields are final and hold no per-transaction data), so one instance serves every
 * protocol version whose rule set contains it. A protocol version that changes a unit's behaviour does not edit it: its
 * delta ({@link RuleSetDelta}) supersedes the unit with another implementation, and the earlier versions keep the
 * original. The implementation must be a named class (not a lambda or an anonymous class) so that the frozen rule-set
 * manifests can name it.</p>
 *
 * <p>A unit does not declare its protocol versions: they are where the composition puts it, from the version whose rule
 * set introduced it ({@link ConwayRuleSet#since(Scope, String)}) to the last one before a delta retired or superseded
 * it ({@link ConwayRuleSets#versionsOf(String)}). So adding a protocol version never edits an existing unit, and the
 * constructor ranges of the pinned catalogue ({@link ConwayPredicate#pvRange()}) are checked against the composition.</p>
 *
 * @param <S> the subject the unit reads: its scope's narrow view of the transition ({@link Scope})
 */
public interface RuleUnit<S> {

    /**
     * @return the stable id: {@code RULE.Constructor} for a check (with {@code #suffix} where one constructor has
     *         several checks), {@code RULE.step} for a state step
     */
    String id();

    UnitKind kind();

    /** @return {@code STATIC} units are skipped when a validated transaction is re-applied (ADR-056 §6) */
    CheckLabel label();

    /** @return where Haskell has the check or step (cardano-ledger {@code f649f975}) */
    String haskellRef();

    /** @return the predicate-failure constructors the unit reports (empty for a step that reports none) */
    default List<ConwayPredicate> reports() {
        return List.of();
    }

    /** @return the unit's parameters, for the manifest (e.g. a table of parameter keys); empty when it has none */
    default String variant() {
        return "";
    }

    /**
     * @return true when a failure of this unit stops the rest of the transition ({@code whenFailureFreeDefault}): only
     *         {@code MEMPOOL}'s all-inputs-spent check
     */
    default boolean haltsOnFailure() {
        return false;
    }

    /**
     * Runs the unit.
     *
     * @return the failures of this one predicate, in the predicate's own order ({@code Validation}'s list); empty when
     *         it holds (a step returns what it must report, usually nothing)
     */
    List<LedgerFailure> apply(S subject);
}
