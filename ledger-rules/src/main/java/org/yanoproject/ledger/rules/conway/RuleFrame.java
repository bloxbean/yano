package org.yanoproject.ledger.rules.conway;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One running Haskell STS rule and the predicate failures it collects, in Haskell's order.
 *
 * <p>Failures accumulate as in {@code small-steps} {@code applyRuleInternal}
 * ({@code Control/State/Transition/Extended.hs:668-731}): a failing predicate prepends its failures reversed
 * ({@code map orElse (reverse errs) <> fs}, :708), and a sub-rule prepends its own final list one failure at a
 * time ({@code traverse_ (\a -> modify (first (a :)))}, :713-724). A rule's final list is therefore the
 * <em>reverse</em> of everything it recorded, in execution order, where a sub-rule's contribution is that
 * sub-rule's own final list. The list is never reversed again, so the order an engine reports depends on the
 * nesting: rooted at {@code LEDGER}, the {@code UTXOW} checks come first in execution order, then
 * {@code UTXOS}, then the {@code UTXO} checks in reverse execution order; and since {@code LEDGER} runs
 * {@code CERTS} before {@code UTXOW}, {@code UTXOW}'s failures come before {@code CERTS}'. (Amaru's scenarios name
 * one predicate that its Haskell checker requires to be among Haskell's failures, not necessarily the first,
 * {@code ValidatePhaseOne/Run.hs:263-270}; for example 00124: {@code ValueNotConservedUTxO} before the
 * {@code InsufficientCollateral} that {@code feesOK} found first; 00278: {@code NoCollateralInputs} before
 * {@code InsufficientCollateral}.)</p>
 *
 * <p>Not thread-safe: one transition runs on one thread.</p>
 */
public final class RuleFrame {

    private final LedgerRuleName rule;
    private final TransitionContext context;
    private final List<LedgerFailure> recorded = new ArrayList<>();

    RuleFrame(LedgerRuleName rule, TransitionContext context) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.context = Objects.requireNonNull(context, "context");
    }

    public LedgerRuleName rule() {
        return rule;
    }

    public TransitionContext context() {
        return context;
    }

    /** Opens a sub-rule; its failures reach this frame through {@link #subRule(RuleFrame)}. */
    public RuleFrame child(LedgerRuleName childRule) {
        return new RuleFrame(childRule, context);
    }

    /**
     * Records one failing predicate ({@code runTest}, {@code failBecause}, {@code ?!}); {@code failures} is the
     * predicate's {@code Validation} error list in its own order. Empty means the predicate held.
     */
    public void predicate(List<LedgerFailure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        recorded.addAll(failures);
        context.markFailing();
    }

    /** Records one failure of a predicate. */
    public void fail(LedgerFailure failure) {
        predicate(List.of(failure));
    }

    /** Folds a finished sub-rule into this rule ({@code trans @sub}). */
    public void subRule(RuleFrame child) {
        recorded.addAll(child.failures());
    }

    /** @return this rule's failure list as Haskell reports it */
    public List<LedgerFailure> failures() {
        List<LedgerFailure> result = new ArrayList<>(recorded);
        Collections.reverse(result);
        return List.copyOf(result);
    }

    /** @return true when this rule itself recorded (or received from sub-rules) a failure */
    public boolean hasFailures() {
        return !recorded.isEmpty();
    }
}
