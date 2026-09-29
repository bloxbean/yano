package org.yanoproject.ledger.rules.conway.ledger;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.certs.CertState;
import org.yanoproject.ledger.rules.conway.certs.CertsRule;
import org.yanoproject.ledger.rules.conway.certs.CertsRule.UndrainedWithdrawals;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.RawProposal;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.utxo.MinFee;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code LEDGER} predicates and state step that run before {@code CERTS} when {@code isValid = True}
 * ({@code conwayLedgerTransitionTRC}, Conway/Rules/Ledger.hs:361-392), in Haskell's order, against the certificate
 * state before the transaction. They are predicates of {@code LEDGER} itself, so they record into its frame, and each
 * is its own predicate: a failure never stops the later ones (small-steps accumulates them).
 *
 * <ol>
 *   <li>{@code ConwayTreasuryValueMismatch} (:364, {@code validateTreasuryValue} :442-454): when the body states a
 *       {@code currentTreasuryValue}, it must equal the treasury of the chain account state the transaction is applied
 *       to ({@code LedgerEnv}'s {@code casTreasury}). The treasury changes only at an epoch boundary (donations are
 *       collected in {@code utxosDonation} until then), so it is the view's epoch treasury
 *       ({@link LedgerView#treasury()}).</li>
 *   <li>{@code ConwayTxRefScriptsSizeTooBig} (:365, {@code validateRefScriptSize} :456-471): the total size of the
 *       reference scripts of the spending ∪ reference inputs ({@code txNonDistinctRefScriptsSize}, an input in both
 *       counted once) at most {@code ppMaxRefScriptSizePerTxG}, a constant 200 KiB in Conway (Conway/PParams.hs:981;
 *       {@link ConwayLedgerConstants}).</li>
 *   <li>{@code ConwayWdrlNotDelegatedToDRep} (from PV 10: {@code unless hardforkConwayBootstrapPhase}, :379-381,
 *       {@code validateWithdrawalsDelegated} :473-488): the
 *       key-hash withdrawal accounts (any network) whose account is missing or has no DRep delegation, against the
 *       accounts <em>before</em> the certificates, so a transaction can withdraw everything and deregister.</li>
 *   <li>From protocol version 11 ({@code hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule}, :383-392):
 *       {@code testIncompleteAndMissingWithdrawals} (Shelley/Rules/Ledger.hs:351-359) —
 *       {@code ConwayWithdrawalsMissingAccounts} (a withdrawal on another network or without an account), then
 *       {@code ConwayIncompleteWithdrawals} (an amount other than the balance) — then the state step before the first
 *       certificate: the dormant-DRep bump, the voting DReps' expiry refresh and the withdrawal drain, applied to
 *       {@link TransitionContext#certState()}. Before 11 {@link CertsRule}'s base case does both (as
 *       {@code WithdrawalsNotInRewardsCERTS}).</li>
 * </ol>
 */
public final class LedgerPreChecks {

    private LedgerPreChecks() {
    }

    /** Runs the pre-checks in {@code ledger}'s frame. */
    public static void apply(RuleFrame ledger) {
        TransitionContext ctx = ledger.context();

        // :364 runTest $ validateTreasuryValue txBody (chainAccountState ^. casTreasuryL)
        ctx.check(ledger, ConwayPredicate.CONWAY_TREASURY_VALUE_MISMATCH, () -> treasuryValueMismatch(ctx));
        // :365 runTest $ validateRefScriptSize pp (utxoState ^. utxoL) tx
        ctx.check(ledger, ConwayPredicate.CONWAY_TX_REF_SCRIPTS_SIZE_TOO_BIG, () -> refScriptsTooBig(ctx));
        // :379-381 unless bootstrap $ runTest $ validateWithdrawalsDelegated accounts tx (pre-certificate accounts)
        ctx.check(ledger, ConwayPredicate.CONWAY_WDRL_NOT_DELEGATED_TO_DREP, () -> notDelegatedToDRep(ctx));

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

    /** @return the failure detail, or null when the body states no treasury value or the right one */
    static String treasuryValueMismatch(TransitionContext ctx) {
        BigInteger submitted = ctx.raw().currentTreasuryValue();
        if (submitted == null) {
            return null;
        }
        BigInteger actual = ctx.preState().treasury().require("treasury");
        return submitted.equals(actual) ? null
                : "Mismatch {mismatchSupplied = Coin " + submitted + ", mismatchExpected = Coin " + actual + "}";
    }

    /** @return the failure detail, or null when the reference scripts fit the per-transaction limit */
    static String refScriptsTooBig(TransitionContext ctx) {
        long total = MinFee.refScriptsSize(ctx);
        long limit = ctx.constants().maxRefScriptSizePerTx();
        return total <= limit ? null : "Mismatch {mismatchSupplied = " + total + ", mismatchExpected = " + limit + "}";
    }

    /**
     * @return the key hashes of withdrawal accounts without a DRep delegation in the incoming accounts, in
     *         {@code Map AccountAddress} order, or null when there are none
     */
    static String notDelegatedToDRep(TransitionContext ctx) {
        List<byte[]> accounts = new ArrayList<>();
        for (RawTransaction.Withdrawal w : ctx.raw().withdrawals()) {
            accounts.add(w.rewardAccount());
        }
        accounts.sort(RawProposal.ACCOUNT_ORDER);
        List<String> notDelegated = new ArrayList<>();
        LedgerView incoming = ctx.preState();
        for (byte[] account : accounts) {
            if ((account[0] & 0x10) != 0) {
                continue; // a script credential: credKeyHash is Nothing
            }
            byte[] hash = new byte[28];
            System.arraycopy(account, 1, hash, 0, 28);
            String keyHash = HexUtil.encodeHexString(hash);
            Optional<AccountState> state = incoming.account(new CredentialKey(CredentialType.KEY, keyHash))
                    .orElseThrowUnavailable();
            if (state.isEmpty() || state.get().drepDelegation() == null) {
                notDelegated.add(keyHash);
            }
        }
        return notDelegated.isEmpty() ? null : "KeyHash " + notDelegated;
    }
}
