package org.yanoproject.ledger.rules.conway.ledger;

import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;

/**
 * The {@code LEDGER} predicates and state step that run before {@code CERTS} when {@code isValid = True}
 * ({@code conwayLedgerTransitionTRC}, Conway/Rules/Ledger.hs:361-392): the {@link ConwayScopes#LEDGER} units of the
 * protocol version's rule set, against the certificate state before the transaction. They are predicates of
 * {@code LEDGER} itself, so they record into its frame, and each is its own predicate: a failure never stops the later
 * ones (small-steps accumulates them).
 *
 * <ol>
 *   <li>{@code ConwayTreasuryValueMismatch} (:364), {@code ConwayTxRefScriptsSizeTooBig} (:365)
 *       ({@link LedgerChecks});</li>
 *   <li>from protocol version 10 ({@code unless hardforkConwayBootstrapPhase}, :379-381)
 *       {@code ConwayWdrlNotDelegatedToDRep};</li>
 *   <li>from protocol version 11 ({@code hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule}, :383-392)
 *       {@code ConwayWithdrawalsMissingAccounts}, {@code ConwayIncompleteWithdrawals}, then the state step before the
 *       first certificate (the dormant-DRep bump, the voting DReps' expiry refresh and the withdrawal drain), applied to
 *       {@link TransitionContext#certState()}. Before 11 the {@code CERTS} base case does both (as
 *       {@code WithdrawalsNotInRewardsCERTS}).</li>
 * </ol>
 */
public final class LedgerPreChecks {

    private LedgerPreChecks() {
    }

    /** Runs the pre-checks in {@code ledger}'s frame. */
    public static void apply(RuleFrame ledger) {
        ledger.run(ConwayScopes.LEDGER, ledger.context());
    }
}
