package org.yanoproject.ledger.rules.conway.ledger;

import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.certs.CertState;
import org.yanoproject.ledger.rules.conway.certs.CertsRule;
import org.yanoproject.ledger.rules.conway.certs.CertsRule.UndrainedWithdrawals;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;

/**
 * The {@code LEDGER} predicates and state step that run before {@code CERTS} when {@code isValid = True}
 * ({@code conwayLedgerTransitionTRC}, Conway/Rules/Ledger.hs:361-392), in Haskell's order, against the certificate
 * state before the transaction. They are predicates of {@code LEDGER} itself, so they record into its frame.
 *
 * <ol>
 *   <li>{@code ConwayTreasuryValueMismatch} (:364) — <b>Phase 5</b>.</li>
 *   <li>{@code ConwayTxRefScriptsSizeTooBig} (:365) — <b>Phase 5</b>.</li>
 *   <li>{@code ConwayWdrlNotDelegatedToDRep} (PV ≥ 10, :379-381, on the pre-certificate accounts) — <b>Phase 5</b>.</li>
 *   <li>From protocol version 11 ({@code hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule}, :383-392):
 *       {@code testIncompleteAndMissingWithdrawals} (Shelley/Rules/Ledger.hs:351-359) —
 *       {@code ConwayWithdrawalsMissingAccounts} (a withdrawal on another network or without an account), then
 *       {@code ConwayIncompleteWithdrawals} (an amount other than the balance) — then the state step before the first
 *       certificate: the dormant-DRep bump, the voting DReps' expiry refresh and the withdrawal drain, applied to
 *       {@link TransitionContext#certState()}. Before 11 {@link CertsRule}'s base case does both (as
 *       {@code WithdrawalsNotInRewardsCERTS}).</li>
 * </ol>
 *
 * <p>Until Phase 5 fills the three slots, the engine must not admit protocol-version-11 transactions (ADR-056 hard
 * gate; the engine is experimental, {@code JavaEngineFactory}).</p>
 */
public final class LedgerPreChecks {

    private LedgerPreChecks() {
    }

    /** Runs the pre-checks in {@code ledger}'s frame. */
    public static void apply(RuleFrame ledger) {
        TransitionContext ctx = ledger.context();

        // Phase 5 slot 1 (:364): runTest $ validateTreasuryValue — ConwayTreasuryValueMismatch.
        // Phase 5 slot 2 (:365): runTest $ validateRefScriptSize — ConwayTxRefScriptsSizeTooBig.
        // Phase 5 slot 3 (:379-381): unless bootstrap $ runTest $ validateWithdrawalsDelegated —
        //                            ConwayWdrlNotDelegatedToDRep, on the pre-certificate accounts.

        if (CertsRule.movesWithdrawalsToLedger(ctx.protocolMajor())) {
            // :386 testIncompleteAndMissingWithdrawals against the incoming accounts (two failOnNonEmptyMap).
            UndrainedWithdrawals undrained = CertsRule.withdrawalsThatDoNotDrainAccounts(ctx, ctx.preState());
            ctx.check(ledger, ConwayPredicate.CONWAY_WITHDRAWALS_MISSING_ACCOUNTS,
                    () -> undrained.missing().isEmpty() ? null : "Withdrawals " + undrained.missing());
            ctx.check(ledger, ConwayPredicate.CONWAY_INCOMPLETE_WITHDRAWALS,
                    () -> undrained.incomplete().isEmpty() ? null : undrained.incomplete().toString());
            // :387-392 updateDormantDRepExpiries, updateVotingDRepExpiries, drainAccounts
            CertState.advancePreCertificate(ctx);
        }
    }
}
