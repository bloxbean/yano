package org.yanoproject.ledger.rules.conway.ledger;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.certs.CertsRule.UndrainedWithdrawals;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
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
 * The {@code LEDGER} predicates that run before {@code CERTS} ({@code conwayLedgerTransitionTRC},
 * Conway/Rules/Ledger.hs:361-392), each a unit of {@code ConwayScopes.LEDGER} over the {@link TransitionContext}; the
 * order and the protocol versions are the rule sets' ({@code ConwayBaseRules}, {@code ConwayDelta10},
 * {@code ConwayDelta11}).
 */
public final class LedgerChecks {

    private LedgerChecks() {
    }

    /**
     * {@code validateTreasuryValue} (:364, 442-454): when the body states a {@code currentTreasuryValue}, it must equal
     * the treasury of the chain account state the transaction is applied to. The treasury changes only at an epoch
     * boundary (donations are collected in {@code utxosDonation} until then), so it is the view's epoch treasury
     * ({@link LedgerView#treasury()}).
     */
    public static final class TreasuryValueMismatch extends PredicateCheck<TransitionContext> {

        public TreasuryValueMismatch() {
            super(ConwayPredicate.CONWAY_TREASURY_VALUE_MISMATCH);
        }

        @Override
        protected String detail(TransitionContext ctx) {
            BigInteger submitted = ctx.raw().currentTreasuryValue();
            if (submitted == null) {
                return null;
            }
            BigInteger actual = ctx.preState().treasury().require("treasury");
            return submitted.equals(actual) ? null
                    : "Mismatch {mismatchSupplied = Coin " + submitted + ", mismatchExpected = Coin " + actual + "}";
        }
    }

    /**
     * {@code validateRefScriptSize} (:365, 456-471): the total size of the reference scripts of the spending ∪
     * reference inputs ({@code txNonDistinctRefScriptsSize}, an input in both counted once) at most
     * {@code ppMaxRefScriptSizePerTxG}, a constant 200 KiB in Conway (Conway/PParams.hs:981).
     */
    public static final class TxRefScriptsSizeTooBig extends PredicateCheck<TransitionContext> {

        public TxRefScriptsSizeTooBig() {
            super(ConwayPredicate.CONWAY_TX_REF_SCRIPTS_SIZE_TOO_BIG);
        }

        @Override
        protected String detail(TransitionContext ctx) {
            long total = MinFee.refScriptsSize(ctx);
            long limit = ctx.constants().maxRefScriptSizePerTx();
            return total <= limit ? null
                    : "Mismatch {mismatchSupplied = " + total + ", mismatchExpected = " + limit + "}";
        }
    }

    /**
     * {@code validateWithdrawalsDelegated} (:379-381, 473-488; {@code unless hardforkConwayBootstrapPhase}, so from
     * protocol version 10): the key-hash withdrawal accounts (any network) whose account is missing or has no DRep
     * delegation, against the accounts <em>before</em> the certificates, in {@code Map AccountAddress} order.
     */
    public static final class WdrlNotDelegatedToDRep extends PredicateCheck<TransitionContext> {

        public WdrlNotDelegatedToDRep() {
            super(ConwayPredicate.CONWAY_WDRL_NOT_DELEGATED_TO_DREP);
        }

        @Override
        protected String detail(TransitionContext ctx) {
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

    /**
     * {@code testIncompleteAndMissingWithdrawals}' first half (Shelley/Rules/Ledger.hs:351-358), which {@code LEDGER}
     * runs from protocol version 11 ({@code hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule}, Ledger.hs:383-386):
     * withdrawals on another network or without an account, against the incoming accounts.
     */
    public static final class WithdrawalsMissingAccounts extends PredicateCheck<TransitionContext> {

        public WithdrawalsMissingAccounts() {
            super(ConwayPredicate.CONWAY_WITHDRAWALS_MISSING_ACCOUNTS);
        }

        @Override
        protected String detail(TransitionContext ctx) {
            UndrainedWithdrawals undrained = ctx.undrainedWithdrawals();
            return undrained.missing().isEmpty() ? null : "Withdrawals " + undrained.missing();
        }
    }

    /**
     * {@code testIncompleteAndMissingWithdrawals}' second half (Shelley/Rules/Ledger.hs:359), after the missing
     * accounts: withdrawals of an amount other than the balance.
     */
    public static final class IncompleteWithdrawals extends PredicateCheck<TransitionContext> {

        public IncompleteWithdrawals() {
            super(ConwayPredicate.CONWAY_INCOMPLETE_WITHDRAWALS);
        }

        @Override
        protected String detail(TransitionContext ctx) {
            UndrainedWithdrawals undrained = ctx.undrainedWithdrawals();
            return undrained.incomplete().isEmpty() ? null : undrained.incomplete().toString();
        }
    }
}
